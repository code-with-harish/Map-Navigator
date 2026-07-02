package com.mapnavigator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory road network graph.
 * Pure Java (no framework dependencies) so the routing core can be
 * compiled and unit-tested standalone.
 */
public class Graph {

    /** An intersection / landmark on the map. */
    public record Node(int id, String name, double lat, double lon) {}

    /** A directed road segment. Congestion is mutable (updated live). */
    public static final class Edge {
        public final int from;
        public final int to;
        public final String roadName;
        public final double distanceKm;
        public final double speedLimitKmh;
        /** 1.0 = free flow, 2.0 = takes twice as long, etc. */
        private volatile double congestion = 1.0;

        public Edge(int from, int to, String roadName, double distanceKm, double speedLimitKmh) {
            this.from = from;
            this.to = to;
            this.roadName = roadName;
            this.distanceKm = distanceKm;
            this.speedLimitKmh = speedLimitKmh;
        }

        public double getCongestion() { return congestion; }
        public void setCongestion(double c) { this.congestion = Math.max(1.0, c); }

        /** Travel time in minutes for a given congestion level and weather factor. */
        public double travelTimeMinutes(double congestionLevel, double weatherSpeedFactor) {
            double effectiveSpeed = (speedLimitKmh * weatherSpeedFactor) / congestionLevel;
            return (distanceKm / Math.max(effectiveSpeed, 1.0)) * 60.0;
        }

        public String key() { return from + "-" + to; }
    }

    private final Map<Integer, Node> nodes = new HashMap<>();
    private final Map<Integer, List<Edge>> adjacency = new HashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private double maxSpeedKmh = 1.0;

    public void addNode(Node n) {
        nodes.put(n.id(), n);
        adjacency.computeIfAbsent(n.id(), k -> new ArrayList<>());
    }

    /** Adds a bidirectional road (two directed edges). */
    public void addRoad(int a, int b, String roadName, double distanceKm, double speedLimitKmh) {
        addDirectedEdge(new Edge(a, b, roadName, distanceKm, speedLimitKmh));
        addDirectedEdge(new Edge(b, a, roadName, distanceKm, speedLimitKmh));
    }

    public void addDirectedEdge(Edge e) {
        adjacency.computeIfAbsent(e.from, k -> new ArrayList<>()).add(e);
        edges.add(e);
        maxSpeedKmh = Math.max(maxSpeedKmh, e.speedLimitKmh);
    }

    public Node node(int id) { return nodes.get(id); }
    public Map<Integer, Node> nodes() { return Collections.unmodifiableMap(nodes); }
    public List<Edge> edges() { return Collections.unmodifiableList(edges); }
    public List<Edge> neighbors(int nodeId) {
        return adjacency.getOrDefault(nodeId, Collections.emptyList());
    }
    public double maxSpeedKmh() { return maxSpeedKmh; }

    /** Haversine distance in km between two nodes. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    /** Nearest node to an arbitrary coordinate (for map clicks). */
    public Node nearestNode(double lat, double lon) {
        Node best = null;
        double bestD = Double.MAX_VALUE;
        for (Node n : nodes.values()) {
            double d = haversineKm(lat, lon, n.lat(), n.lon());
            if (d < bestD) { bestD = d; best = n; }
        }
        return best;
    }
}
