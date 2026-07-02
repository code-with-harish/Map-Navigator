package com.mapnavigator.web;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mapnavigator.core.AStarNavigator;
import com.mapnavigator.core.AStarNavigator.Route;
import com.mapnavigator.core.AStarNavigator.RouteSegment;
import com.mapnavigator.core.Graph;
import com.mapnavigator.core.RouteProfile;
import com.mapnavigator.traffic.SensorNetwork;
import com.mapnavigator.traffic.TrafficPredictor;
import com.mapnavigator.traffic.TrafficService;
import com.mapnavigator.traffic.WeatherService;
import com.mapnavigator.web.dto.ErrorView;
import com.mapnavigator.web.dto.IncidentRequest;
import com.mapnavigator.web.dto.NodeView;
import com.mapnavigator.web.dto.RouteView;

/**
 * Map and routing API.
 *
 *   GET    /api/network                        nodes + roads (for drawing the map)
 *   GET    /api/route?from=1&to=9              fastest route on live traffic
 *            &departIn=45                      predictive route for departure in 45 min
 *            &profile=fastest|shortest|eco     cost model to optimise
 *            &alternatives=3                   up to 3 meaningfully different routes
 *            fromLat/fromLon/toLat/toLon       coordinates instead of node ids
 *   GET    /api/traffic                        live congestion + closed roads
 *   GET    /api/weather                        current condition and speed factor
 *   GET    /api/sensors                        latest roadside sensor readings
 *   GET    /api/incidents                      active accidents and closures
 *   POST   /api/incidents                      report one {from, to, type, severity, durationMinutes}
 *   DELETE /api/incidents/{id}                 clear one early
 *   GET    /api/predict?from=1&to=2            learned 24h congestion profile for a road
 */
@RestController
@RequestMapping("/api")
public class MapController {

    private static final Logger log = LoggerFactory.getLogger(MapController.class);

    private static final int MAX_ALTERNATIVES = 3;
    private static final double MAX_DEPART_IN_MINUTES = 12 * 60;
    /** ETA confidence half-width for an ~80% band. */
    private static final double CONFIDENCE_Z = 1.28;
    /** The band is never claimed tighter than ±30s or wider than ±35% of the ETA. */
    private static final double MIN_BAND_MINUTES = 0.5;
    private static final double MAX_BAND_SHARE = 0.35;

    private final Graph graph;
    private final AStarNavigator navigator;
    private final TrafficService traffic;
    private final TrafficPredictor predictor;
    private final WeatherService weather;

    public MapController(Graph graph, AStarNavigator navigator, TrafficService traffic,
                         WeatherService weather) {
        this.graph = graph;
        this.navigator = navigator;
        this.traffic = traffic;
        this.predictor = traffic.predictor();
        this.weather = weather;
    }

    @GetMapping("/network")
    public Map<String, Object> network() {
        List<NodeView> nodes = graph.nodes().values().stream()
                .map(n -> new NodeView(n.id(), n.name(), n.lat(), n.lon()))
                .toList();
        List<Map<String, Object>> roads = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Graph.Edge e : graph.edges()) {
            String undirected = Math.min(e.from, e.to) + "-" + Math.max(e.from, e.to);
            if (!seen.add(undirected)) continue;
            roads.add(Map.of("from", e.from, "to", e.to, "road", e.roadName,
                    "distanceKm", e.distanceKm, "speedLimitKmh", e.speedLimitKmh,
                    "oneWay", graph.edge(e.to, e.from) == null));
        }
        return Map.of("nodes", nodes, "edges", roads);
    }

    @GetMapping("/route")
    public ResponseEntity<?> route(@RequestParam(required = false) Integer from,
                                   @RequestParam(required = false) Integer to,
                                   @RequestParam(required = false) Double fromLat,
                                   @RequestParam(required = false) Double fromLon,
                                   @RequestParam(required = false) Double toLat,
                                   @RequestParam(required = false) Double toLon,
                                   @RequestParam(defaultValue = "0") double departIn,
                                   @RequestParam(defaultValue = "fastest") String profile,
                                   @RequestParam(defaultValue = "1") int alternatives) {
        Integer startId = from, goalId = to;
        if (startId == null && fromLat != null && fromLon != null) {
            startId = graph.nearestNode(fromLat, fromLon).id();
        }
        if (goalId == null && toLat != null && toLon != null) {
            goalId = graph.nearestNode(toLat, toLon).id();
        }
        if (startId == null || goalId == null) {
            return badRequest("Provide from/to node ids or fromLat/fromLon/toLat/toLon");
        }
        if (graph.node(startId) == null) return badRequest("Unknown node id " + startId);
        if (graph.node(goalId) == null) return badRequest("Unknown node id " + goalId);

        RouteProfile routeProfile = RouteProfile.parse(profile);
        double horizon = clamp(departIn, 0, MAX_DEPART_IN_MINUTES);
        int wanted = (int) clamp(alternatives, 1, MAX_ALTERNATIVES);

        LocalTime now = LocalTime.now();
        ToDoubleFunction<Graph.Edge> congestionOf = horizon <= 0
                ? Graph.Edge::getCongestion
                : e -> predictor.predict(e, now.getHour(), now.getMinute(), horizon);
        double weatherFactor = weather.speedFactor();

        List<Route> routes = wanted == 1
                ? singleRoute(startId, goalId, congestionOf, weatherFactor, routeProfile)
                : navigator.findAlternatives(startId, goalId, congestionOf, weatherFactor,
                        routeProfile, wanted);
        if (routes.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorView("No route found between "
                            + graph.node(startId).name() + " and " + graph.node(goalId).name()));
        }

        List<RouteView> views = routes.stream()
                .map(r -> toView(r, routeProfile, horizon, weatherFactor))
                .toList();

        // top level mirrors the best route so pre-existing clients keep working
        RouteView best = views.get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", best.from());
        body.put("to", best.to());
        body.put("profile", best.profile());
        body.put("predictive", best.predictive());
        body.put("departInMinutes", best.departInMinutes());
        body.put("path", best.path());
        body.put("segments", best.segments());
        body.put("totalDistanceKm", best.totalDistanceKm());
        body.put("etaMinutes", best.etaMinutes());
        body.put("etaConfidenceMinutes", best.etaConfidenceMinutes());
        body.put("alternatives", views.subList(1, views.size()));
        body.put("weather", weather.current());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/traffic")
    public Map<String, Object> trafficSnapshot() {
        return Map.of("timestamp", System.currentTimeMillis(),
                "congestion", traffic.congestionSnapshot(),
                "closed", traffic.closedEdgeKeys());
    }

    @GetMapping("/weather")
    public WeatherService.Weather weather() {
        return weather.current();
    }

    @GetMapping("/sensors")
    public List<SensorNetwork.SensorReading> sensors() {
        return traffic.sensors().latestReadings();
    }

    @GetMapping("/incidents")
    public List<TrafficService.Incident> incidents() {
        return traffic.activeIncidents();
    }

    @PostMapping("/incidents")
    public ResponseEntity<?> reportIncident(@RequestBody IncidentRequest request) {
        if (request.from() == null || request.to() == null) {
            return badRequest("from and to node ids are required");
        }
        TrafficService.IncidentType type = parseIncidentType(request.type());
        double severity = request.severity() == null ? 2.0 : request.severity();
        double duration = request.durationMinutes() == null ? 30.0 : request.durationMinutes();
        TrafficService.Incident incident =
                traffic.reportIncident(request.from(), request.to(), type, severity, duration);
        log.info("{} reported on {} ({} -> {}), duration {} min",
                type, incident.roadName(), incident.from(), incident.to(), duration);
        return ResponseEntity.status(HttpStatus.CREATED).body(incident);
    }

    @DeleteMapping("/incidents/{id}")
    public ResponseEntity<?> clearIncident(@PathVariable long id) {
        return traffic.clearIncident(id)
                ? ResponseEntity.ok(Map.of("cleared", id))
                : ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorView("No incident with id " + id));
    }

    @GetMapping("/predict")
    public ResponseEntity<?> predict(@RequestParam int from, @RequestParam int to) {
        Graph.Edge edge = graph.edge(from, to);
        if (edge == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorView("No road segment " + from + " -> " + to));
        }
        return ResponseEntity.ok(Map.of(
                "from", from, "to", to, "road", edge.roadName,
                "hourlyCongestionProfile", predictor.profile(edge),
                "forecastStdDev", round1(predictor.stdDev(edge, 0))));
    }

    private List<Route> singleRoute(int startId, int goalId,
                                    ToDoubleFunction<Graph.Edge> congestionOf,
                                    double weatherFactor, RouteProfile profile) {
        Route r = navigator.findRoute(startId, goalId, congestionOf, weatherFactor, profile);
        return r == null ? List.of() : List.of(r);
    }

    private RouteView toView(Route route, RouteProfile profile, double horizon,
                             double weatherFactor) {
        List<NodeView> path = route.nodeIds().stream()
                .map(graph::node)
                .map(n -> new NodeView(n.id(), n.name(), n.lat(), n.lon()))
                .toList();
        return new RouteView(path.get(0).name(), path.get(path.size() - 1).name(),
                profile.name().toLowerCase(), horizon > 0, horizon, path, route.segments(),
                route.totalDistanceKm(), route.totalMinutes(),
                confidenceBand(route, horizon, weatherFactor));
    }

    /**
     * Half-width of an ~80% band around the ETA. Each segment contributes its
     * sensitivity to congestion (minutes per congestion unit) times the
     * predictor's forecast std-dev at the moment the segment would be driven;
     * variances are summed assuming independent segments.
     */
    private double confidenceBand(Route route, double departIn, double weatherFactor) {
        double variance = 0;
        double minutesInto = departIn;
        for (RouteSegment s : route.segments()) {
            Graph.Edge edge = graph.edge(s.from(), s.to());
            if (edge == null) continue;
            double minutesPerCongestionUnit = edge.travelTimeMinutes(1.0, weatherFactor);
            double sd = predictor.stdDev(edge, minutesInto);
            variance += Math.pow(minutesPerCongestionUnit * sd, 2);
            minutesInto += s.minutes();
        }
        double band = CONFIDENCE_Z * Math.sqrt(variance);
        band = Math.min(band, route.totalMinutes() * MAX_BAND_SHARE);
        return round1(Math.max(band, MIN_BAND_MINUTES));
    }

    private static TrafficService.IncidentType parseIncidentType(String value) {
        if (value == null || value.isBlank()) return TrafficService.IncidentType.ACCIDENT;
        try {
            return TrafficService.IncidentType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown incident type '" + value + "' (expected accident or closure)");
        }
    }

    private static ResponseEntity<ErrorView> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ErrorView(message));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
