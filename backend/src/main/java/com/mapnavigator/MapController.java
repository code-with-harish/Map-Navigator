package com.mapnavigator;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public REST API.
 *
 *   GET /api/network                          - nodes + edges (for drawing the map)
 *   GET /api/route?from=1&to=9                - fastest route on LIVE traffic
 *   GET /api/route?from=1&to=9&departIn=45    - PREDICTIVE route for departure in 45 min
 *   GET /api/route?fromLat=..&fromLon=..&toLat=..&toLon=..  - route between coordinates
 *   GET /api/traffic                          - live congestion per road segment
 *   GET /api/weather                          - current weather + speed factor
 *   GET /api/sensors                          - latest IoT sensor readings
 *   GET /api/predict?from=1&to=2              - learned 24h congestion profile for a segment
 */
@RestController
@RequestMapping("/api")
public class MapController {

    private final Graph graph;
    private final AStarNavigator navigator;
    private final TrafficDataService traffic;
    private final WeatherService weather;
    private final MLModel mlModel;

    public MapController(Graph graph, AStarNavigator navigator, TrafficDataService traffic,
                         WeatherService weather, MLModel mlModel) {
        this.graph = graph;
        this.navigator = navigator;
        this.traffic = traffic;
        this.weather = weather;
        this.mlModel = mlModel;
    }

    @GetMapping("/network")
    public Map<String, Object> network() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Graph.Node n : graph.nodes().values()) {
            nodes.add(Map.of("id", n.id(), "name", n.name(), "lat", n.lat(), "lon", n.lon()));
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Graph.Edge e : graph.edges()) {
            if (e.from < e.to) { // one entry per bidirectional road
                edges.add(Map.of("from", e.from, "to", e.to, "road", e.roadName,
                        "distanceKm", e.distanceKm, "speedLimitKmh", e.speedLimitKmh));
            }
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    @GetMapping("/route")
    public ResponseEntity<?> route(@RequestParam(required = false) Integer from,
                                   @RequestParam(required = false) Integer to,
                                   @RequestParam(required = false) Double fromLat,
                                   @RequestParam(required = false) Double fromLon,
                                   @RequestParam(required = false) Double toLat,
                                   @RequestParam(required = false) Double toLon,
                                   @RequestParam(defaultValue = "0") double departIn) {
        Integer startId = from, goalId = to;
        if (startId == null && fromLat != null && fromLon != null) {
            startId = graph.nearestNode(fromLat, fromLon).id();
        }
        if (goalId == null && toLat != null && toLon != null) {
            goalId = graph.nearestNode(toLat, toLon).id();
        }
        if (startId == null || goalId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Provide from/to node ids or fromLat/fromLon/toLat/toLon"));
        }

        LocalTime now = LocalTime.now();
        double horizon = Math.max(0, departIn);
        AStarNavigator.Route route = navigator.findRoute(startId, goalId,
                e -> horizon <= 0
                        ? e.getCongestion()
                        : mlModel.predict(e, now.getHour(), now.getMinute(), horizon),
                weather.speedFactor());

        if (route == null) {
            return ResponseEntity.status(404).body(Map.of("error", "No route found"));
        }

        List<Map<String, Object>> path = new ArrayList<>();
        for (int id : route.nodeIds()) {
            Graph.Node n = graph.node(id);
            path.add(Map.of("id", n.id(), "name", n.name(), "lat", n.lat(), "lon", n.lon()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("from", graph.node(startId).name());
        body.put("to", graph.node(goalId).name());
        body.put("predictive", horizon > 0);
        body.put("departInMinutes", horizon);
        body.put("path", path);
        body.put("segments", route.segments());
        body.put("totalDistanceKm", route.totalDistanceKm());
        body.put("etaMinutes", route.totalMinutes());
        body.put("weather", weather.current());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/traffic")
    public Map<String, Object> trafficSnapshot() {
        return Map.of("timestamp", System.currentTimeMillis(), "congestion", traffic.snapshot());
    }

    @GetMapping("/weather")
    public WeatherService.Weather weather() {
        return weather.current();
    }

    @GetMapping("/sensors")
    public List<IoTIntegration.SensorReading> sensors() {
        return traffic.iot().latestReadings();
    }

    @GetMapping("/predict")
    public ResponseEntity<?> predict(@RequestParam int from, @RequestParam int to) {
        for (Graph.Edge e : graph.edges()) {
            if (e.from == from && e.to == to) {
                return ResponseEntity.ok(Map.of(
                        "from", from, "to", to, "road", e.roadName,
                        "hourlyCongestionProfile", mlModel.profile(e)));
            }
        }
        return ResponseEntity.status(404).body(Map.of("error", "No such road segment"));
    }
}
