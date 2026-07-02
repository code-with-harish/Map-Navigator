package com.mapnavigator;

import static com.mapnavigator.TestKit.check;
import static com.mapnavigator.TestKit.note;
import static com.mapnavigator.TestKit.section;

import java.time.LocalTime;
import java.util.List;

import com.mapnavigator.core.AStarNavigator;
import com.mapnavigator.core.Graph;
import com.mapnavigator.core.RoadNetwork;
import com.mapnavigator.traffic.SensorNetwork;
import com.mapnavigator.traffic.TrafficPredictor;
import com.mapnavigator.traffic.TrafficService;
import com.mapnavigator.traffic.WeatherService;

final class TrafficTests {

    private TrafficTests() {}

    static void run() {
        Graph g = RoadNetwork.embedded();
        TrafficPredictor predictor = new TrafficPredictor();
        SensorNetwork sensors = new SensorNetwork(g, 42);
        TrafficService traffic = new TrafficService(g, sensors, predictor, 42);
        AStarNavigator nav = new AStarNavigator(g);

        section("Time-of-day congestion");
        traffic.warmUp(3);
        traffic.tick(LocalTime.of(18, 30));
        double rushAvg = averageCongestion(g);
        traffic.tick(LocalTime.of(3, 0));
        double nightAvg = averageCongestion(g);
        note(String.format("avg congestion: rush=%.2f night=%.2f", rushAvg, nightAvg));
        check(rushAvg > nightAvg * 1.3, "rush hour clearly more congested than night");

        section("Sensors");
        List<SensorNetwork.SensorReading> readings = sensors.latestReadings();
        check(!readings.isEmpty(), "sensors produce readings (" + readings.size() + ")");
        check(readings.stream().allMatch(r -> r.avgSpeedKmh() >= 0), "speeds are non-negative");

        section("Reported accidents");
        traffic.tick(LocalTime.of(3, 0)); // low baseline so the spike is visible
        Graph.Edge edge = g.edge(1, 2);
        double before = edge.getCongestion();
        TrafficService.Incident accident =
                traffic.reportIncident(1, 2, TrafficService.IncidentType.ACCIDENT, 3.0, 30);
        check(edge.getCongestion() >= before + 2.9, "accident inflates congestion immediately ("
                + before + " -> " + edge.getCongestion() + ")");
        check(traffic.activeIncidents().stream().anyMatch(i -> i.id() == accident.id()),
                "accident listed as active");
        check(traffic.clearIncident(accident.id()), "accident can be cleared");
        check(!traffic.clearIncident(accident.id()), "clearing twice fails");

        section("Road closures");
        AStarNavigator.Route open = nav.findRoute(5, 9, Graph.Edge::getCongestion, 1.0);
        AStarNavigator.RouteSegment leg = open.segments().get(0);
        TrafficService.Incident closure = traffic.reportIncident(
                leg.from(), leg.to(), TrafficService.IncidentType.CLOSURE, 1.0, 60);
        check(g.edge(leg.from(), leg.to()).isClosed()
                        && g.edge(leg.to(), leg.from()).isClosed(),
                "closure closes both directions");
        check(traffic.closedEdgeKeys().size() == 2, "closed edges appear in snapshot");
        AStarNavigator.Route detour = nav.findRoute(5, 9, Graph.Edge::getCongestion, 1.0);
        check(detour != null && detour.segments().stream()
                        .noneMatch(s -> s.from() == leg.from() && s.to() == leg.to()),
                "routing detours around the closure");
        traffic.clearIncident(closure.id());
        check(!g.edge(leg.from(), leg.to()).isClosed(), "clearing a closure reopens the road");
        boolean invalidRejected = false;
        try {
            traffic.reportIncident(1, 20, TrafficService.IncidentType.CLOSURE, 1.0, 10);
        } catch (IllegalArgumentException ex) {
            invalidRejected = true;
        }
        check(invalidRejected, "incident on a non-existent road is rejected");

        section("Weather");
        WeatherService w = new WeatherService(7);
        boolean inRange = true;
        for (int i = 0; i < 500; i++) {
            w.tick();
            double f = w.speedFactor();
            if (f < 0.70 || f > 1.0) inRange = false;
        }
        check(inRange, "weather speed factor stays within [0.70, 1.0] over 500 ticks");
    }

    private static double averageCongestion(Graph g) {
        return g.edges().stream().mapToDouble(Graph.Edge::getCongestion).average().orElse(0);
    }
}
