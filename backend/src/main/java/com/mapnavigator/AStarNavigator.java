package com.mapnavigator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.ToDoubleFunction;

/**
 * A* shortest-path engine that minimises predicted travel TIME (not distance).
 *
 * Edge cost  = distance / (speedLimit * weatherFactor / congestion)
 * Heuristic  = straight-line (haversine) distance / max speed in network
 *
 * The heuristic is admissible because no edge can ever be traversed faster
 * than the network's maximum speed limit in clear weather, so A* is optimal.
 *
 * Congestion per edge is supplied by a pluggable function, which lets the
 * caller route on live traffic (TrafficDataService) or on ML-predicted
 * future traffic (MLModel) for predictive routing.
 */
public class AStarNavigator {

    /** One leg of a route. */
    public record RouteSegment(int from, int to, String roadName, double distanceKm,
                               double congestion, double minutes) {}

    /** Result of a routing query. */
    public record Route(List<Integer> nodeIds, List<RouteSegment> segments,
                        double totalDistanceKm, double totalMinutes) {}

    private final Graph graph;

    public AStarNavigator(Graph graph) {
        this.graph = graph;
    }

    /**
     * Finds the fastest route from start to goal.
     *
     * @param congestionOf  returns the congestion level (>= 1.0) to assume for an edge
     * @param weatherFactor global speed multiplier from weather (1.0 = clear)
     * @return the fastest route, or null if the goal is unreachable
     */
    public Route findRoute(int startId, int goalId,
                           ToDoubleFunction<Graph.Edge> congestionOf,
                           double weatherFactor) {
        Graph.Node goal = graph.node(goalId);
        if (graph.node(startId) == null || goal == null) return null;

        // minutes per km at the best possible speed -> admissible heuristic
        double bestMinutesPerKm = 60.0 / graph.maxSpeedKmh();

        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Graph.Edge> cameBy = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));

        gScore.put(startId, 0.0);
        open.add(new double[]{startId, heuristic(startId, goal, bestMinutesPerKm)});

        while (!open.isEmpty()) {
            int current = (int) open.poll()[0];
            if (current == goalId) return buildRoute(startId, goalId, cameBy, congestionOf, weatherFactor);

            double g = gScore.getOrDefault(current, Double.MAX_VALUE);
            for (Graph.Edge e : graph.neighbors(current)) {
                double congestion = Math.max(1.0, congestionOf.applyAsDouble(e));
                double tentative = g + e.travelTimeMinutes(congestion, weatherFactor);
                if (tentative < gScore.getOrDefault(e.to, Double.MAX_VALUE)) {
                    gScore.put(e.to, tentative);
                    cameBy.put(e.to, e);
                    open.add(new double[]{e.to, tentative + heuristic(e.to, goal, bestMinutesPerKm)});
                }
            }
        }
        return null; // unreachable
    }

    private double heuristic(int nodeId, Graph.Node goal, double bestMinutesPerKm) {
        Graph.Node n = graph.node(nodeId);
        return Graph.haversineKm(n.lat(), n.lon(), goal.lat(), goal.lon()) * bestMinutesPerKm;
    }

    private Route buildRoute(int startId, int goalId, Map<Integer, Graph.Edge> cameBy,
                             ToDoubleFunction<Graph.Edge> congestionOf, double weatherFactor) {
        List<Graph.Edge> path = new ArrayList<>();
        int cursor = goalId;
        while (cursor != startId) {
            Graph.Edge e = cameBy.get(cursor);
            if (e == null) return null;
            path.add(e);
            cursor = e.from;
        }
        Collections.reverse(path);

        List<Integer> ids = new ArrayList<>();
        List<RouteSegment> segments = new ArrayList<>();
        ids.add(startId);
        double totalKm = 0, totalMin = 0;
        for (Graph.Edge e : path) {
            double congestion = Math.max(1.0, congestionOf.applyAsDouble(e));
            double minutes = e.travelTimeMinutes(congestion, weatherFactor);
            segments.add(new RouteSegment(e.from, e.to, e.roadName, e.distanceKm,
                    round2(congestion), round2(minutes)));
            ids.add(e.to);
            totalKm += e.distanceKm;
            totalMin += minutes;
        }
        return new Route(ids, segments, round2(totalKm), round2(totalMin));
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
