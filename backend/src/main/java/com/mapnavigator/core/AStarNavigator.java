package com.mapnavigator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * A* route planner over the road network.
 *
 * The cost being minimised depends on the requested {@link RouteProfile}:
 * predicted travel time (fastest), distance (shortest), or a fuel-consumption
 * proxy (eco). Each profile supplies an admissible, consistent haversine-based
 * heuristic, so the first time the goal is settled the route is optimal for
 * that cost model. Closed roads are never expanded.
 *
 * Congestion per edge is supplied by a pluggable function, which lets the
 * caller route on live traffic (TrafficDataService) or on predicted future
 * traffic (TrafficPredictor) with the same engine.
 *
 * On sizing: the network is a few dozen nodes, and even a city-scale OSM
 * extract stays comfortably inside plain A*'s budget for interactive queries.
 * Speed-up structures like contraction hierarchies or ALT only pay for
 * themselves on country-scale graphs and would make live per-query edge
 * weights (the whole point of this system) much harder, so they are
 * deliberately not used here.
 */
public class AStarNavigator {

    /** One leg of a route. */
    public record RouteSegment(int from, int to, String roadName, double distanceKm,
                               double congestion, double minutes) {}

    /** Result of a routing query. ETA minutes are reported for every profile. */
    public record Route(List<Integer> nodeIds, List<RouteSegment> segments,
                        double totalDistanceKm, double totalMinutes) {}

    private record QueueEntry(int node, double f) {}

    /** Maximum detour factor an alternative route may cost relative to the best route. */
    private static final double ALT_MAX_COST_FACTOR = 1.6;
    /** An alternative must differ from every already-accepted route by at least this share of its length. */
    private static final double ALT_MAX_OVERLAP = 0.75;
    /** Multiplier applied per penalty round to edges of already-found routes. */
    private static final double ALT_EDGE_PENALTY = 1.45;

    private final Graph graph;

    public AStarNavigator(Graph graph) {
        this.graph = graph;
    }

    /** Fastest route on the given congestion model; kept for API compatibility. */
    public Route findRoute(int startId, int goalId,
                           ToDoubleFunction<Graph.Edge> congestionOf,
                           double weatherFactor) {
        return findRoute(startId, goalId, congestionOf, weatherFactor, RouteProfile.FASTEST);
    }

    /**
     * Finds the optimal route from start to goal for the given profile.
     *
     * @param congestionOf  congestion level (>= 1.0) to assume for an edge
     * @param weatherFactor global speed multiplier from weather (1.0 = clear)
     * @return the optimal route, or null if the goal is unreachable
     */
    public Route findRoute(int startId, int goalId,
                           ToDoubleFunction<Graph.Edge> congestionOf,
                           double weatherFactor, RouteProfile profile) {
        Map<Integer, Graph.Edge> cameBy =
                search(startId, goalId, congestionOf, weatherFactor, profile, e -> 1.0);
        return cameBy == null ? null
                : buildRoute(startId, goalId, cameBy, congestionOf, weatherFactor);
    }

    /**
     * Finds up to {@code maxRoutes} meaningfully different routes, best first.
     *
     * Uses the penalty method: after each accepted route, the cost of its
     * edges is inflated and the search re-run; candidates that mostly overlap
     * an accepted route or cost far more than the best one are discarded.
     * Reported segment times are always the true (unpenalised) values.
     */
    public List<Route> findAlternatives(int startId, int goalId,
                                        ToDoubleFunction<Graph.Edge> congestionOf,
                                        double weatherFactor, RouteProfile profile,
                                        int maxRoutes) {
        List<Route> accepted = new ArrayList<>();
        Route best = findRoute(startId, goalId, congestionOf, weatherFactor, profile);
        if (best == null) return accepted;
        accepted.add(best);

        Map<String, Double> penalty = new HashMap<>();
        for (int attempt = 0; accepted.size() < maxRoutes && attempt < maxRoutes * 3; attempt++) {
            for (RouteSegment s : accepted.get(accepted.size() - 1).segments()) {
                penalty.merge(s.from() + "-" + s.to(), ALT_EDGE_PENALTY, (a, b) -> a * b);
                penalty.merge(s.to() + "-" + s.from(), ALT_EDGE_PENALTY, (a, b) -> a * b);
            }
            Map<Integer, Graph.Edge> cameBy = search(startId, goalId, congestionOf, weatherFactor,
                    profile, e -> penalty.getOrDefault(e.key(), 1.0));
            if (cameBy == null) break;
            Route candidate = buildRoute(startId, goalId, cameBy, congestionOf, weatherFactor);
            if (candidate == null) break;

            if (candidate.totalMinutes() <= best.totalMinutes() * ALT_MAX_COST_FACTOR
                    && maxOverlap(candidate, accepted) <= ALT_MAX_OVERLAP) {
                accepted.add(candidate);
            } else {
                // still penalise the candidate's edges so the next attempt explores elsewhere
                for (RouteSegment s : candidate.segments()) {
                    penalty.merge(s.from() + "-" + s.to(), ALT_EDGE_PENALTY, (a, b) -> a * b);
                }
            }
        }
        return accepted;
    }

    /** Core A*. Returns the predecessor map, or null if the goal is unreachable. */
    private Map<Integer, Graph.Edge> search(int startId, int goalId,
                                            ToDoubleFunction<Graph.Edge> congestionOf,
                                            double weatherFactor, RouteProfile profile,
                                            ToDoubleFunction<Graph.Edge> extraPenalty) {
        Graph.Node goal = graph.node(goalId);
        if (graph.node(startId) == null || goal == null) return null;

        double hPerKm = profile.heuristicPerKm(graph);
        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Graph.Edge> cameBy = new HashMap<>();
        Set<Integer> settled = new HashSet<>();
        PriorityQueue<QueueEntry> open =
                new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));

        gScore.put(startId, 0.0);
        open.add(new QueueEntry(startId, heuristic(startId, goal, hPerKm)));

        while (!open.isEmpty()) {
            int current = open.poll().node();
            if (!settled.add(current)) continue;   // stale queue entry
            if (current == goalId) return cameBy;

            double g = gScore.get(current);
            for (Graph.Edge e : graph.neighbors(current)) {
                if (e.isClosed() || settled.contains(e.to)) continue;
                double congestion = Math.max(1.0, congestionOf.applyAsDouble(e));
                double cost = profile.edgeCost(e, congestion, weatherFactor)
                        * Math.max(1.0, extraPenalty.applyAsDouble(e));
                double tentative = g + cost;
                if (tentative < gScore.getOrDefault(e.to, Double.MAX_VALUE)) {
                    gScore.put(e.to, tentative);
                    cameBy.put(e.to, e);
                    open.add(new QueueEntry(e.to, tentative + heuristic(e.to, goal, hPerKm)));
                }
            }
        }
        return null; // unreachable
    }

    private double heuristic(int nodeId, Graph.Node goal, double hPerKm) {
        Graph.Node n = graph.node(nodeId);
        return Graph.haversineKm(n.lat(), n.lon(), goal.lat(), goal.lon()) * hPerKm;
    }

    /** Share of the candidate's length that runs along the closest already-accepted route. */
    private static double maxOverlap(Route candidate, List<Route> accepted) {
        double worst = 0;
        for (Route r : accepted) {
            Set<String> keys = new HashSet<>();
            for (RouteSegment s : r.segments()) keys.add(s.from() + "-" + s.to());
            double shared = 0;
            for (RouteSegment s : candidate.segments()) {
                if (keys.contains(s.from() + "-" + s.to())) shared += s.distanceKm();
            }
            worst = Math.max(worst, shared / Math.max(candidate.totalDistanceKm(), 1e-9));
        }
        return worst;
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
