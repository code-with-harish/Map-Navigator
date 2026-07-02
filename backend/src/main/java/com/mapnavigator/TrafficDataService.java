package com.mapnavigator;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Real-time traffic engine.
 *
 * On every tick it computes a live congestion level for each road segment by
 * fusing three signals:
 *
 *   1. Baseline: the typical time-of-day pattern (rush-hour peaks).
 *   2. IoT sensors: where a roadside sensor exists, its *measured* average
 *      speed overrides the baseline (sensors also simulate incidents).
 *   3. Noise: small per-segment stochastic variation.
 *
 * Every observation is also streamed into the {@link MLModel} so the
 * predictive router keeps learning the network's daily profile online.
 */
public class TrafficDataService {

    private final Graph graph;
    private final IoTIntegration iot;
    private final MLModel mlModel;
    private final Random random;

    public TrafficDataService(Graph graph, IoTIntegration iot, MLModel mlModel, long seed) {
        this.graph = graph;
        this.iot = iot;
        this.mlModel = mlModel;
        this.random = new Random(seed);
    }

    /** Advances the traffic simulation one tick for the given wall-clock time. */
    public void tick(LocalTime now) {
        int hour = now.getHour();

        // 1) baseline + noise for every segment
        for (Graph.Edge e : graph.edges()) {
            double base = MLModel.typicalHourFactor(hour);
            double noise = 0.9 + random.nextDouble() * 0.25;
            e.setCongestion(base * noise);
        }

        // 2) sensor fusion: measured speed wins where we have a sensor
        Map<String, Double> measured = new HashMap<>();
        for (IoTIntegration.SensorReading r : iot.poll(hour)) {
            measured.put(r.from() + "-" + r.to(), r.avgSpeedKmh());
        }
        for (Graph.Edge e : graph.edges()) {
            Double speed = measured.get(e.key());
            if (speed != null && speed > 0) {
                e.setCongestion(e.speedLimitKmh / speed);
            }
        }

        // 3) feed the predictive model
        for (Graph.Edge e : graph.edges()) {
            mlModel.observe(e, e.getCongestion(), hour);
        }
    }

    /** Warm-starts the ML model by replaying a few days of simulated traffic. */
    public void warmUp(int days) {
        for (int d = 0; d < days; d++) {
            for (int h = 0; h < 24; h++) {
                tick(LocalTime.of(h, 0));
            }
        }
    }

    /** Live congestion snapshot, keyed by "from-to". */
    public Map<String, Double> snapshot() {
        Map<String, Double> out = new HashMap<>();
        for (Graph.Edge e : graph.edges()) {
            out.put(e.key(), Math.round(e.getCongestion() * 100) / 100.0);
        }
        return out;
    }

    public Graph graph() { return graph; }
    public MLModel mlModel() { return mlModel; }
    public IoTIntegration iot() { return iot; }
}
