package com.mapnavigator;

import static com.mapnavigator.TestKit.check;
import static com.mapnavigator.TestKit.note;
import static com.mapnavigator.TestKit.section;

import com.mapnavigator.core.Graph;
import com.mapnavigator.core.RoadNetwork;
import com.mapnavigator.traffic.SensorNetwork;
import com.mapnavigator.traffic.TrafficPredictor;
import com.mapnavigator.traffic.TrafficService;

final class PredictionTests {

    private PredictionTests() {}

    static void run() {
        Graph g = RoadNetwork.embedded();
        TrafficPredictor predictor = new TrafficPredictor();
        TrafficService traffic = new TrafficService(g, new SensorNetwork(g, 42), predictor, 42);
        traffic.warmUp(4);
        Graph.Edge edge = g.edges().get(0);

        section("Learned daily profile");
        double[] profile = predictor.profile(edge);
        note(String.format("profile: 03:00=%.2f 09:00=%.2f 18:00=%.2f",
                profile[3], profile[9], profile[18]));
        check(profile[18] > profile[3] * 1.3, "evening rush learned to be worse than night");
        check(profile[9] > profile[3] * 1.2, "morning rush learned to be worse than night");
        double predRush = predictor.predict(edge, 14, 0, 240);  // arrive 18:00
        double predNight = predictor.predict(edge, 14, 0, 600); // arrive 24:00
        check(predRush > predNight * 1.3, "forecast: rush-hour arrival worse than midnight arrival");

        section("Minute-level smoothness");
        double maxStep = 0;
        double prev = predictor.predict(edge, 16, 0, 0);
        for (int m = 1; m <= 180; m++) {
            double p = predictor.predict(edge, 16, 0, m);
            maxStep = Math.max(maxStep, Math.abs(p - prev));
            prev = p;
        }
        note(String.format("max minute-to-minute forecast step: %.3f", maxStep));
        check(maxStep < 0.15, "forecast varies smoothly minute to minute (no hourly jumps)");

        section("Incident decay");
        for (int i = 0; i < 5; i++) predictor.observe(edge, 5.0, 14); // sudden jam at 14:00
        double now = predictor.predict(edge, 14, 0, 0);
        double in30 = predictor.predict(edge, 14, 0, 30);
        double in120 = predictor.predict(edge, 14, 0, 120);
        note(String.format("after jam: now=%.2f +30min=%.2f +120min=%.2f", now, in30, in120));
        check(now > 3.0, "live forecast reflects the jam");
        check(in30 < now, "jam effect decays over 30 minutes");
        check(in120 < in30, "jam effect keeps decaying");
        check(in120 < profile[16] + 1.0, "two hours out, forecast is close to the daily profile");

        section("Uncertainty");
        double sd0 = predictor.stdDev(edge, 0);
        double sd60 = predictor.stdDev(edge, 60);
        double sd600 = predictor.stdDev(edge, 600);
        note(String.format("stddev: now=%.2f +60min=%.2f +600min=%.2f", sd0, sd60, sd600));
        check(sd0 > 0, "uncertainty is positive");
        check(sd60 > sd0, "uncertainty grows with the forecast horizon");
        check(sd600 <= 2.0, "uncertainty is capped");
    }
}
