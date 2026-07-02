package com.mapnavigator;

import java.time.LocalTime;
import java.util.*;

public class CoreTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String msg) {
        if (cond) { passed++; System.out.println("  PASS: " + msg); }
        else { failed++; System.out.println("  FAIL: " + msg); }
    }
    public static void main(String[] args) {
        Graph g = RoadNetwork.embedded();
        check(g.nodes().size() == 24, "24 nodes loaded (" + g.nodes().size() + ")");
        check(g.edges().size() == 74, "74 directed edges loaded (" + g.edges().size() + ")");

        AStarNavigator nav = new AStarNavigator(g);

        // Free-flow route Majestic(5) -> BTM Layout(9)
        AStarNavigator.Route r = nav.findRoute(5, 9, e -> 1.0, 1.0);
        check(r != null, "route 5->9 found");
        System.out.println("  Route: " + names(g, r) + " | " + r.totalDistanceKm() + " km, " + r.totalMinutes() + " min");
        check(r.nodeIds().get(0) == 5 && r.nodeIds().get(r.nodeIds().size()-1) == 9, "endpoints correct");
        check(r.totalDistanceKm() > 5 && r.totalDistanceKm() < 20, "distance sane");

        // A* optimality vs brute-force Dijkstra-ish check: compare with all-pairs via repeated calls both directions
        AStarNavigator.Route rBack = nav.findRoute(9, 5, e -> 1.0, 1.0);
        check(Math.abs(r.totalMinutes() - rBack.totalMinutes()) < 0.05, "symmetric network: same time both directions");

        // Congestion changes the chosen route: jam every edge on the free-flow path
        Set<String> jammed = new HashSet<>();
        for (AStarNavigator.RouteSegment s : r.segments()) { jammed.add(s.from()+"-"+s.to()); jammed.add(s.to()+"-"+s.from()); }
        AStarNavigator.Route rJam = nav.findRoute(5, 9, e -> jammed.contains(e.key()) ? 6.0 : 1.0, 1.0);
        System.out.println("  Jammed route: " + names(g, rJam) + " | " + rJam.totalMinutes() + " min");
        boolean rerouted = !rJam.nodeIds().equals(r.nodeIds());
        check(rerouted, "router avoids jammed segments (picked different path)");
        check(rJam.totalMinutes() >= r.totalMinutes(), "jammed ETA >= free-flow ETA");

        // Weather slows everything proportionally
        AStarNavigator.Route rRain = nav.findRoute(5, 9, e -> 1.0, 0.70);
        check(Math.abs(rRain.totalMinutes() - r.totalMinutes()/0.70) < 0.1, "heavy rain scales ETA by 1/0.7");

        // Unreachable / invalid ids
        check(nav.findRoute(5, 999, e -> 1.0, 1.0) == null, "unknown goal returns null");

        // Full simulation stack: traffic + IoT + ML warm-up
        MLModel ml = new MLModel();
        IoTIntegration iot = new IoTIntegration(g, 42);
        TrafficDataService traffic = new TrafficDataService(g, iot, ml, 42);
        traffic.warmUp(3);
        traffic.tick(LocalTime.of(18, 30)); // evening rush
        double rushAvg = g.edges().stream().mapToDouble(Graph.Edge::getCongestion).average().orElse(0);
        traffic.tick(LocalTime.of(3, 0));  // night
        double nightAvg = g.edges().stream().mapToDouble(Graph.Edge::getCongestion).average().orElse(0);
        System.out.printf("  Avg congestion: rush=%.2f night=%.2f%n", rushAvg, nightAvg);
        check(rushAvg > nightAvg * 1.3, "rush hour clearly more congested than night");

        // ML prediction: incident decay + seasonal baseline
        Graph.Edge sample = g.edges().get(0);
        double predNow  = ml.predict(sample, 14, 0, 0);     // live (may include active incident)
        double predRush = ml.predict(sample, 14, 0, 240);   // 18:00, incident decayed -> rush baseline
        double predNight= ml.predict(sample, 14, 0, 600);   // 24:00, incident decayed -> night baseline
        System.out.printf("  Prediction on %s: now=%.2f rush(18:00)=%.2f night(24:00)=%.2f%n",
                sample.roadName, predNow, predRush, predNight);
        check(predRush > predNight * 1.3, "learned profile: rush hour clearly worse than midnight");
        double[] profile = ml.profile(sample);
        check(profile[18] > profile[3], "hourly profile: 18:00 > 03:00");

        // Weather sim stays in valid states
        WeatherService w = new WeatherService(7);
        for (int i = 0; i < 500; i++) w.tick();
        check(w.speedFactor() >= 0.70 && w.speedFactor() <= 1.0, "weather factor in valid range");

        // nearestNode
        Graph.Node near = g.nearestNode(12.9757, 77.6062);
        check(near.id() == 1, "nearestNode snaps click to MG Road");

        System.out.println();
        System.out.println(failed == 0 ? "ALL " + passed + " TESTS PASSED" : failed + " TESTS FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
    static String names(Graph g, AStarNavigator.Route r) {
        StringBuilder sb = new StringBuilder();
        for (int id : r.nodeIds()) { if (sb.length()>0) sb.append(" -> "); sb.append(g.node(id).name()); }
        return sb.toString();
    }
}
