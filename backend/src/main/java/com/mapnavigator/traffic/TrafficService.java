package com.mapnavigator.traffic;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import com.mapnavigator.core.Graph;

/**
 * Real-time traffic engine.
 *
 * On every tick it recomputes each road segment's congestion in four steps:
 *
 *   1. Baseline: the typical time-of-day pattern (rush-hour peaks) plus
 *      per-segment noise.
 *   2. Local conditions: instrumented segments get the slow-moving drift of
 *      their roadside sensor ({@link SensorNetwork}).
 *   3. Incidents: active accidents inflate congestion on the affected road;
 *      closures take it out of the network entirely. Incidents come from the
 *      simulation itself (random crashes) or from the API (reported by users
 *      or operators), and expire on their own.
 *   4. Every resulting observation is streamed into the
 *      {@link TrafficPredictor} so predictive routing keeps learning online.
 */
public class TrafficService {

    public enum IncidentType { ACCIDENT, CLOSURE }

    public record Incident(long id, int from, int to, String roadName, IncidentType type,
                           double severity, long startedAtMs, long endsAtMs) {
        public boolean activeAt(long nowMs) { return nowMs < endsAtMs; }
    }

    /** Probability per tick of a spontaneous simulated accident somewhere in the network. */
    private static final double ACCIDENT_PROBABILITY = 0.02;

    private final Graph graph;
    private final SensorNetwork sensors;
    private final TrafficPredictor predictor;
    private final Random random;
    private final ConcurrentMap<Long, Incident> incidents = new ConcurrentHashMap<>();
    private final AtomicLong incidentIds = new AtomicLong(1);

    public TrafficService(Graph graph, SensorNetwork sensors, TrafficPredictor predictor, long seed) {
        this.graph = graph;
        this.sensors = sensors;
        this.predictor = predictor;
        this.random = new Random(seed);
    }

    /** Advances the traffic simulation one tick for the given wall-clock time. */
    public void tick(LocalTime now) {
        int hour = now.getHour();
        long nowMs = System.currentTimeMillis();

        for (Graph.Edge e : graph.edges()) {
            double base = TrafficPredictor.typicalHourFactor(hour);
            double noise = 0.9 + random.nextDouble() * 0.25;
            e.setCongestion(base * noise);
        }

        sensors.applyLocalConditions();
        maybeSpawnAccident(nowMs);
        applyIncidents(nowMs);
        sensors.measure();

        for (Graph.Edge e : graph.edges()) {
            predictor.observe(e, e.getCongestion(), hour);
        }
    }

    /** Warm-starts the predictor by replaying a few days of simulated traffic. */
    public void warmUp(int days) {
        for (int d = 0; d < days; d++) {
            for (int h = 0; h < 24; h++) {
                tick(LocalTime.of(h, 0));
            }
        }
    }

    /**
     * Registers an incident on the road between two nodes (both directions).
     * A CLOSURE removes the road from routing until it expires; an ACCIDENT
     * adds {@code severity} to its congestion.
     *
     * @throws IllegalArgumentException if no road connects the two nodes
     */
    public Incident reportIncident(int from, int to, IncidentType type,
                                   double severity, double durationMinutes) {
        Graph.Edge edge = graph.edge(from, to);
        if (edge == null) edge = graph.edge(to, from);
        if (edge == null) {
            throw new IllegalArgumentException("No road between nodes " + from + " and " + to);
        }
        long nowMs = System.currentTimeMillis();
        Incident incident = new Incident(incidentIds.getAndIncrement(),
                edge.from, edge.to, edge.roadName, type,
                clamp(severity, 0.5, 5.0),
                nowMs, nowMs + (long) (clamp(durationMinutes, 1, 24 * 60) * 60_000));
        incidents.put(incident.id(), incident);
        applyIncidents(nowMs); // take effect immediately, not on the next tick
        return incident;
    }

    /** Clears an incident early. Returns false if it doesn't exist. */
    public boolean clearIncident(long id) {
        Incident removed = incidents.remove(id);
        if (removed == null) return false;
        if (removed.type() == IncidentType.CLOSURE) {
            setClosedBothWays(removed.from(), removed.to(), false);
        }
        return true;
    }

    public List<Incident> activeIncidents() {
        long nowMs = System.currentTimeMillis();
        List<Incident> out = new ArrayList<>();
        for (Incident i : incidents.values()) {
            if (i.activeAt(nowMs)) out.add(i);
        }
        out.sort((a, b) -> Long.compare(b.startedAtMs(), a.startedAtMs()));
        return out;
    }

    /** Live congestion snapshot, keyed by "from-to". */
    public Map<String, Double> congestionSnapshot() {
        Map<String, Double> out = new HashMap<>();
        for (Graph.Edge e : graph.edges()) {
            out.put(e.key(), Math.round(e.getCongestion() * 100) / 100.0);
        }
        return out;
    }

    /** Keys ("from-to") of currently closed directed edges. */
    public List<String> closedEdgeKeys() {
        List<String> out = new ArrayList<>();
        for (Graph.Edge e : graph.edges()) {
            if (e.isClosed()) out.add(e.key());
        }
        return out;
    }

    public Graph graph() { return graph; }
    public TrafficPredictor predictor() { return predictor; }
    public SensorNetwork sensors() { return sensors; }

    private void maybeSpawnAccident(long nowMs) {
        if (random.nextDouble() >= ACCIDENT_PROBABILITY) return;
        List<Graph.Edge> edges = graph.edges();
        Graph.Edge edge = edges.get(random.nextInt(edges.size()));
        Incident incident = new Incident(incidentIds.getAndIncrement(),
                edge.from, edge.to, edge.roadName, IncidentType.ACCIDENT,
                1.0 + random.nextDouble() * 2.0,
                nowMs, nowMs + (3 + random.nextInt(8)) * 60_000L);
        incidents.put(incident.id(), incident);
    }

    private void applyIncidents(long nowMs) {
        for (Map.Entry<Long, Incident> entry : incidents.entrySet()) {
            Incident i = entry.getValue();
            if (!i.activeAt(nowMs)) {
                incidents.remove(entry.getKey());
                if (i.type() == IncidentType.CLOSURE) {
                    setClosedBothWays(i.from(), i.to(), false);
                }
                continue;
            }
            if (i.type() == IncidentType.CLOSURE) {
                setClosedBothWays(i.from(), i.to(), true);
            } else {
                addCongestionBothWays(i.from(), i.to(), i.severity());
            }
        }
    }

    private void setClosedBothWays(int a, int b, boolean closed) {
        Graph.Edge ab = graph.edge(a, b);
        Graph.Edge ba = graph.edge(b, a);
        if (ab != null) ab.setClosed(closed);
        if (ba != null) ba.setClosed(closed);
    }

    private void addCongestionBothWays(int a, int b, double severity) {
        Graph.Edge ab = graph.edge(a, b);
        Graph.Edge ba = graph.edge(b, a);
        if (ab != null) ab.setCongestion(ab.getCongestion() + severity);
        if (ba != null) ba.setCongestion(ba.getCongestion() + severity);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
