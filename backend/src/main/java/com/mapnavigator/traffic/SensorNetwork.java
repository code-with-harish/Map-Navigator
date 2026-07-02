package com.mapnavigator.traffic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.mapnavigator.core.Graph;

/**
 * Simulated network of roadside sensors.
 *
 * Each sensor sits on a road segment, adds a slow-moving local perturbation
 * to that segment's congestion (the fine-grained detail that probe or loop
 * data adds on top of a city-wide model), and reports the resulting measured
 * average speed and a vehicle count. Because readings are taken from the
 * segment's final congestion for the tick, sensors also see accidents and
 * closures injected by the traffic service, exactly as real hardware would.
 */
public class SensorNetwork {

    public record SensorReading(String sensorId, int from, int to, String roadName,
                                double avgSpeedKmh, int vehicleCount, long timestampMs) {}

    private static final class Sensor {
        final String id;
        final Graph.Edge edge;
        double drift = 1.0; // slow random walk around 1.0

        Sensor(String id, Graph.Edge edge) { this.id = id; this.edge = edge; }
    }

    private final List<Sensor> sensors = new ArrayList<>();
    private final ConcurrentMap<String, SensorReading> latest = new ConcurrentHashMap<>();
    private final Random random;

    /** Instruments roughly every third road segment. */
    public SensorNetwork(Graph graph, long seed) {
        this.random = new Random(seed);
        int i = 0;
        for (Graph.Edge e : graph.edges()) {
            if (i % 3 == 0) sensors.add(new Sensor("SNS-" + String.format("%03d", i), e));
            i++;
        }
    }

    /** Applies each sensor's local drift to its segment. Called before readings are taken. */
    public void applyLocalConditions() {
        for (Sensor s : sensors) {
            s.drift = clamp(s.drift + (random.nextDouble() - 0.5) * 0.08, 0.85, 1.4);
            s.edge.setCongestion(s.edge.getCongestion() * s.drift);
        }
    }

    /** Measures the current state of every instrumented segment. */
    public List<SensorReading> measure() {
        long now = System.currentTimeMillis();
        List<SensorReading> out = new ArrayList<>(sensors.size());
        for (Sensor s : sensors) {
            double congestion = s.edge.getCongestion();
            double avgSpeed = s.edge.isClosed()
                    ? 0.0
                    : s.edge.speedLimitKmh / Math.max(1.0, congestion);
            int vehicles = s.edge.isClosed()
                    ? 0
                    : (int) (congestion * (20 + random.nextInt(30)));
            SensorReading r = new SensorReading(s.id, s.edge.from, s.edge.to, s.edge.roadName,
                    Math.round(avgSpeed * 10) / 10.0, vehicles, now);
            latest.put(s.id, r);
            out.add(r);
        }
        return out;
    }

    public List<SensorReading> latestReadings() {
        return new ArrayList<>(latest.values());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
