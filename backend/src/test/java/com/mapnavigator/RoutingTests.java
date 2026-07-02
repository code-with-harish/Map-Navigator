package com.mapnavigator;

import static com.mapnavigator.TestKit.check;
import static com.mapnavigator.TestKit.checkClose;
import static com.mapnavigator.TestKit.note;
import static com.mapnavigator.TestKit.section;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import com.mapnavigator.core.AStarNavigator;
import com.mapnavigator.core.AStarNavigator.Route;
import com.mapnavigator.core.AStarNavigator.RouteSegment;
import com.mapnavigator.core.Graph;
import com.mapnavigator.core.RoadNetwork;
import com.mapnavigator.core.RouteProfile;

final class RoutingTests {

    private RoutingTests() {}

    static void run() {
        Graph g = RoadNetwork.embedded();
        AStarNavigator nav = new AStarNavigator(g);

        section("Network loading");
        check(g.nodes().size() == 24, "24 nodes loaded (" + g.nodes().size() + ")");
        check(g.edges().size() == 74, "74 directed edges loaded (" + g.edges().size() + ")");
        check(g.edge(1, 2) != null && g.edge(2, 1) != null, "edge lookup works both directions");
        check(g.edge(1, 99) == null, "edge lookup returns null for missing edge");

        section("Basic routing");
        Route r = nav.findRoute(5, 9, e -> 1.0, 1.0);
        check(r != null, "route Majestic -> BTM Layout found");
        note(describe(g, r));
        check(r.nodeIds().get(0) == 5 && r.nodeIds().get(r.nodeIds().size() - 1) == 9,
                "endpoints correct");
        check(r.totalDistanceKm() > 5 && r.totalDistanceKm() < 20, "distance sane");
        Route rBack = nav.findRoute(9, 5, e -> 1.0, 1.0);
        checkClose(rBack.totalMinutes(), r.totalMinutes(), 0.05,
                "symmetric network: same time both directions");
        check(nav.findRoute(5, 999, e -> 1.0, 1.0) == null, "unknown goal returns null");
        Route self = nav.findRoute(5, 5, e -> 1.0, 1.0);
        check(self != null && self.segments().isEmpty(), "start == goal yields empty route");

        section("A* optimality vs Dijkstra reference (all pairs)");
        ToDoubleFunction<Graph.Edge> bumpy =
                e -> 1.0 + ((e.from * 31 + e.to * 17) % 23) / 10.0; // deterministic uneven traffic
        int mismatches = 0;
        for (int a : g.nodes().keySet()) {
            for (int b : g.nodes().keySet()) {
                if (a == b) continue;
                Route ar = nav.findRoute(a, b, bumpy, 0.9);
                double dj = dijkstraMinutes(g, a, b, bumpy, 0.9);
                if (ar == null || Math.abs(ar.totalMinutes() - dj) > 0.05) mismatches++;
            }
        }
        check(mismatches == 0, "A* matches Dijkstra on all "
                + (g.nodes().size() * (g.nodes().size() - 1)) + " pairs (" + mismatches + " mismatches)");

        section("Traffic-aware rerouting");
        Set<String> jammed = new HashSet<>();
        for (RouteSegment s : r.segments()) {
            jammed.add(s.from() + "-" + s.to());
            jammed.add(s.to() + "-" + s.from());
        }
        Route rJam = nav.findRoute(5, 9, e -> jammed.contains(e.key()) ? 6.0 : 1.0, 1.0);
        note("jammed: " + describe(g, rJam));
        check(!rJam.nodeIds().equals(r.nodeIds()), "router avoids jammed segments");
        check(rJam.totalMinutes() >= r.totalMinutes(), "jammed ETA >= free-flow ETA");

        section("Weather");
        Route rRain = nav.findRoute(5, 9, e -> 1.0, 0.70);
        checkClose(rRain.totalMinutes(), r.totalMinutes() / 0.70, 0.1,
                "heavy rain scales ETA by 1/0.7");

        section("Road closures");
        RouteSegment first = r.segments().get(0);
        g.edge(first.from(), first.to()).setClosed(true);
        g.edge(first.to(), first.from()).setClosed(true);
        Route rClosed = nav.findRoute(5, 9, e -> 1.0, 1.0);
        check(rClosed != null, "route still found with one road closed");
        boolean usesClosed = rClosed.segments().stream()
                .anyMatch(s -> s.from() == first.from() && s.to() == first.to());
        check(!usesClosed, "closed road is never used");
        for (Graph.Edge e : g.neighbors(9)) e.setClosed(true);
        for (Graph.Edge e : g.edges()) if (e.to == 9) e.setClosed(true);
        check(nav.findRoute(5, 9, e -> 1.0, 1.0) == null,
                "goal with all roads closed is unreachable");
        for (Graph.Edge e : g.edges()) e.setClosed(false);

        section("Route profiles");
        ToDoubleFunction<Graph.Edge> live = bumpy;
        Route fastest = nav.findRoute(5, 20, live, 1.0, RouteProfile.FASTEST);
        Route shortest = nav.findRoute(5, 20, live, 1.0, RouteProfile.SHORTEST);
        Route eco = nav.findRoute(5, 20, live, 1.0, RouteProfile.ECO);
        note("fastest : " + fastest.totalMinutes() + " min, " + fastest.totalDistanceKm() + " km");
        note("shortest: " + shortest.totalMinutes() + " min, " + shortest.totalDistanceKm() + " km");
        note("eco     : " + eco.totalMinutes() + " min, " + eco.totalDistanceKm() + " km");
        check(fastest.totalMinutes() <= shortest.totalMinutes() + 0.01,
                "fastest route is never slower than shortest route");
        check(shortest.totalDistanceKm() <= fastest.totalDistanceKm() + 0.01,
                "shortest route is never longer than fastest route");
        check(shortest.totalDistanceKm() <= eco.totalDistanceKm() + 0.01,
                "shortest route is never longer than eco route");
        check(RouteProfile.parse("eco") == RouteProfile.ECO
                && RouteProfile.parse(null) == RouteProfile.FASTEST, "profile parsing");
        boolean rejected = false;
        try { RouteProfile.parse("teleport"); } catch (IllegalArgumentException ex) { rejected = true; }
        check(rejected, "unknown profile is rejected");

        section("Alternative routes");
        List<Route> alts = nav.findAlternatives(5, 9, live, 1.0, RouteProfile.FASTEST, 3);
        check(!alts.isEmpty(), "at least one route returned");
        checkClose(alts.get(0).totalMinutes(),
                nav.findRoute(5, 9, live, 1.0).totalMinutes(), 0.05,
                "first alternative is the optimal route");
        Set<List<Integer>> unique = new HashSet<>();
        for (Route alt : alts) unique.add(alt.nodeIds());
        check(unique.size() == alts.size(), "alternatives are distinct paths ("
                + alts.size() + " returned)");
        for (Route alt : alts) {
            check(alt.totalMinutes() <= alts.get(0).totalMinutes() * 1.6 + 0.01,
                    "alternative within 1.6x of best ("
                            + alt.totalMinutes() + " vs " + alts.get(0).totalMinutes() + ")");
        }

        section("Coordinate snapping");
        check(g.nearestNode(12.9757, 77.6062).id() == 1, "nearestNode snaps click to MG Road");
    }

    /** Plain Dijkstra used as an independent reference for A* optimality. */
    private static double dijkstraMinutes(Graph g, int start, int goal,
                                          ToDoubleFunction<Graph.Edge> congestionOf,
                                          double weatherFactor) {
        Map<Integer, Double> dist = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>((x, y) -> Double.compare(x[1], y[1]));
        dist.put(start, 0.0);
        pq.add(new double[]{start, 0.0});
        Set<Integer> done = new HashSet<>();
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int u = (int) top[0];
            if (!done.add(u)) continue;
            if (u == goal) return top[1];
            for (Graph.Edge e : g.neighbors(u)) {
                if (e.isClosed()) continue;
                double c = Math.max(1.0, congestionOf.applyAsDouble(e));
                double nd = top[1] + e.travelTimeMinutes(c, weatherFactor);
                if (nd < dist.getOrDefault(e.to, Double.MAX_VALUE)) {
                    dist.put(e.to, nd);
                    pq.add(new double[]{e.to, nd});
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static String describe(Graph g, Route r) {
        List<String> names = new ArrayList<>();
        for (int id : r.nodeIds()) names.add(g.node(id).name());
        return String.join(" -> ", names)
                + "  |  " + r.totalDistanceKm() + " km, " + r.totalMinutes() + " min";
    }
}
