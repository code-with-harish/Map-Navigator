package com.mapnavigator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simulated network of roadside IoT sensors.
 *
 * Each sensor sits on a road segment and periodically reports the average
 * measured vehicle speed and a vehicle count. TrafficDataService fuses these
 * readings with its baseline model: where a sensor exists, the *measured*
 * speed overrides the modelled congestion, which is exactly how a real
 * deployment would blend probe/sensor data with historical models.
 *
 * The simulation itself models each sensor's road with a smoothly drifting
 * congestion value plus occasional random "incidents" (a crash or stalled
 * vehicle) that spike congestion for a while - giving the system realistic,
 * non-uniform live data to react to.
 */
public class IoTIntegration {

    public record SensorReading(String sensorId, int from, int to, String roadName,
                                double avgSpeedKmh, int vehicleCount, long timestampMs) {}

    private static final class Sensor {
        final String id;
        final Graph.Edge edge;
        double drift = 1.0;          // slow-moving local congestion component
        double incidentSeverity = 0; // > 0 while an incident is active
        int incidentTicksLeft = 0;

        Sensor(String id, Graph.Edge edge) { this.id = id; this.edge = edge; }
    }

    private final List<Sensor> sensors = new ArrayList<>();
    private final ConcurrentMap<String, SensorReading> latest = new ConcurrentHashMap<>();
    private final Random random;

    /** Instruments roughly every third road segment with a sensor. */
    public IoTIntegration(Graph graph, long seed) {
        this.random = new Random(seed);
        int i = 0;
        for (Graph.Edge e : graph.edges()) {
            if (i % 3 == 0) sensors.add(new Sensor("SNS-" + String.format("%03d", i), e));
            i++;
        }
    }

    /** Advances the simulation one tick and emits fresh readings. */
    public List<SensorReading> poll(int hourOfDay) {
        long now = System.currentTimeMillis();
        List<SensorReading> out = new ArrayList<>(sensors.size());
        for (Sensor s : sensors) {
            // smooth random walk around 1.0
            s.drift = clamp(s.drift + (random.nextDouble() - 0.5) * 0.08, 0.85, 1.4);

            // rare incidents: ~0.5% chance per tick, lasting 20-60 ticks
            if (s.incidentTicksLeft == 0 && random.nextDouble() < 0.005) {
                s.incidentTicksLeft = 20 + random.nextInt(40);
                s.incidentSeverity = 1.0 + random.nextDouble() * 2.0;
            }
            if (s.incidentTicksLeft > 0) s.incidentTicksLeft--;
            double incident = s.incidentTicksLeft > 0 ? s.incidentSeverity : 0.0;

            double congestion = MLModel.typicalHourFactor(hourOfDay) * s.drift + incident;
            double avgSpeed = s.edge.speedLimitKmh / Math.max(1.0, congestion);
            int vehicles = (int) (congestion * (20 + random.nextInt(30)));

            SensorReading r = new SensorReading(s.id, s.edge.from, s.edge.to,
                    s.edge.roadName, Math.round(avgSpeed * 10) / 10.0, vehicles, now);
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
