package com.mapnavigator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight predictive traffic model used for predictive routing.
 *
 * Two components are learned online per road segment:
 *
 *  1. A time-of-day congestion profile (24 hourly buckets) updated with an
 *     exponential moving average from every observation the traffic service
 *     feeds in. This captures the recurring daily pattern (rush hours).
 *
 *  2. A short-term "current deviation" state: how much the live congestion
 *     currently differs from the historical value for this hour (e.g. an
 *     accident making a road much slower than usual for a Tuesday 10am).
 *
 * A prediction for `minutesAhead` blends the two: the current deviation is
 * assumed to decay with a configurable half-life, while the baseline follows
 * the (possibly different) hour bucket of the arrival time. This is a classic
 * exponential-smoothing / seasonal-baseline forecaster - small, fast, fully
 * online, and with no external ML dependencies.
 */
public class MLModel {

    private static final double PROFILE_LEARNING_RATE = 0.05;
    private static final double DEVIATION_LEARNING_RATE = 0.4;
    private static final double DEVIATION_HALF_LIFE_MIN = 30.0;

    private static final class EdgeState {
        final double[] hourlyProfile = new double[24];
        double currentDeviation = 0.0; // live congestion minus profile for current hour

        EdgeState(double seedBase) {
            for (int h = 0; h < 24; h++) hourlyProfile[h] = seedBase * typicalHourFactor(h);
        }
    }

    private final Map<String, EdgeState> states = new ConcurrentHashMap<>();

    /** Typical urban congestion shape: morning and evening rush-hour peaks. */
    public static double typicalHourFactor(int hour) {
        if (hour >= 8 && hour <= 10) return 1.9;   // morning peak
        if (hour == 11 || hour == 7) return 1.5;
        if (hour >= 17 && hour <= 20) return 2.1;  // evening peak
        if (hour == 16 || hour == 21) return 1.5;
        if (hour >= 12 && hour <= 15) return 1.25; // midday
        if (hour >= 23 || hour <= 5) return 1.0;   // night, free flow
        return 1.15;
    }

    private EdgeState state(Graph.Edge e) {
        return states.computeIfAbsent(e.key(), k -> new EdgeState(1.0));
    }

    /** Feed one live observation (called by TrafficDataService on every tick). */
    public void observe(Graph.Edge edge, double observedCongestion, int hourOfDay) {
        EdgeState s = state(edge);
        synchronized (s) {
            double predicted = s.hourlyProfile[hourOfDay];
            // learn the seasonal baseline slowly...
            s.hourlyProfile[hourOfDay] += PROFILE_LEARNING_RATE * (observedCongestion - predicted);
            // ...and the short-term deviation quickly
            double deviation = observedCongestion - s.hourlyProfile[hourOfDay];
            s.currentDeviation += DEVIATION_LEARNING_RATE * (deviation - s.currentDeviation);
        }
    }

    /**
     * Predicts congestion on an edge `minutesAhead` from now.
     *
     * @param nowHour       current hour of day (0-23)
     * @param nowMinute     current minute of hour (0-59)
     * @param minutesAhead  forecast horizon (0 = now)
     */
    public double predict(Graph.Edge edge, int nowHour, int nowMinute, double minutesAhead) {
        EdgeState s = state(edge);
        int totalMinutes = nowHour * 60 + nowMinute + (int) Math.round(minutesAhead);
        int targetHour = (totalMinutes / 60) % 24;
        double decay = Math.pow(0.5, minutesAhead / DEVIATION_HALF_LIFE_MIN);
        double prediction;
        synchronized (s) {
            prediction = s.hourlyProfile[targetHour] + s.currentDeviation * decay;
        }
        return Math.max(1.0, prediction);
    }

    /** Learned hourly profile for an edge (for the /api/predict endpoint & debugging). */
    public double[] profile(Graph.Edge edge) {
        EdgeState s = state(edge);
        synchronized (s) {
            return s.hourlyProfile.clone();
        }
    }
}
