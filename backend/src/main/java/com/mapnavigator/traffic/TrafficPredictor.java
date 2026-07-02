package com.mapnavigator.traffic;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mapnavigator.core.Graph;

/**
 * Online traffic forecaster used for predictive routing.
 *
 * Three quantities are learned per road segment, from every observation the
 * traffic service feeds in:
 *
 *  1. A time-of-day congestion profile (24 hourly buckets, exponential moving
 *     average) capturing the recurring daily pattern. Predictions interpolate
 *     linearly between adjacent buckets so forecasts vary smoothly by the
 *     minute instead of jumping on the hour.
 *
 *  2. A short-term deviation: how much live congestion currently differs from
 *     the profile (e.g. an accident making a road much slower than a normal
 *     Tuesday 10am). Forecasts decay this deviation with a 30-minute
 *     half-life, so "the jam will have cleared in an hour" and "but you'll
 *     hit evening rush when you arrive" are both predicted correctly.
 *
 *  3. The variance of one-step prediction errors, which drives the ETA
 *     confidence band: segments whose traffic is erratic widen the band,
 *     and uncertainty grows with the forecast horizon.
 *
 * This is a seasonal-baseline exponential smoother: fully online, a few
 * hundred bytes per segment, microsecond inference, no dependencies. With
 * one simulated observation stream per edge there is nothing for heavier
 * models (gradient boosting, sequence models) to learn that this cannot,
 * and they would drag in training pipelines the project doesn't need.
 */
public class TrafficPredictor {

    private static final double PROFILE_LEARNING_RATE = 0.05;
    private static final double DEVIATION_LEARNING_RATE = 0.4;
    private static final double VARIANCE_LEARNING_RATE = 0.05;
    private static final double DEVIATION_HALF_LIFE_MIN = 30.0;
    /** Horizon (minutes) over which forecast std-dev roughly doubles. */
    private static final double UNCERTAINTY_GROWTH_MIN = 45.0;
    private static final double MAX_STD_DEV = 2.0;

    private static final class EdgeState {
        final double[] hourlyProfile = new double[24];
        double currentDeviation = 0.0;
        double errorVariance = 0.04;

        EdgeState() {
            for (int h = 0; h < 24; h++) hourlyProfile[h] = typicalHourFactor(h);
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
        return states.computeIfAbsent(e.key(), k -> new EdgeState());
    }

    /** Feeds one live observation (called by the traffic service on every tick). */
    public void observe(Graph.Edge edge, double observedCongestion, int hourOfDay) {
        EdgeState s = state(edge);
        synchronized (s) {
            double predictedBefore = s.hourlyProfile[hourOfDay] + s.currentDeviation;
            double err = observedCongestion - predictedBefore;
            s.errorVariance += VARIANCE_LEARNING_RATE * (err * err - s.errorVariance);

            // learn the seasonal baseline slowly...
            s.hourlyProfile[hourOfDay] +=
                    PROFILE_LEARNING_RATE * (observedCongestion - s.hourlyProfile[hourOfDay]);
            // ...and the short-term deviation quickly
            double deviation = observedCongestion - s.hourlyProfile[hourOfDay];
            s.currentDeviation += DEVIATION_LEARNING_RATE * (deviation - s.currentDeviation);
        }
    }

    /**
     * Predicts congestion on an edge {@code minutesAhead} from now.
     *
     * @param nowHour      current hour of day (0-23)
     * @param nowMinute    current minute of hour (0-59)
     * @param minutesAhead forecast horizon (0 = now)
     */
    public double predict(Graph.Edge edge, int nowHour, int nowMinute, double minutesAhead) {
        EdgeState s = state(edge);
        double target = nowHour * 60 + nowMinute + Math.max(0, minutesAhead);
        double decay = Math.pow(0.5, Math.max(0, minutesAhead) / DEVIATION_HALF_LIFE_MIN);
        synchronized (s) {
            return Math.max(1.0, interpolatedBaseline(s, target) + s.currentDeviation * decay);
        }
    }

    /**
     * Standard deviation of the congestion forecast for an edge at the given
     * horizon. Grows with the horizon and is capped, since beyond a couple of
     * hours the seasonal profile dominates and errors stop compounding.
     */
    public double stdDev(Graph.Edge edge, double minutesAhead) {
        EdgeState s = state(edge);
        double base;
        synchronized (s) {
            base = Math.sqrt(s.errorVariance);
        }
        return Math.min(MAX_STD_DEV,
                base * (1.0 + Math.max(0, minutesAhead) / UNCERTAINTY_GROWTH_MIN));
    }

    /** Learned hourly profile for an edge (for the /api/predict endpoint). */
    public double[] profile(Graph.Edge edge) {
        EdgeState s = state(edge);
        synchronized (s) {
            return s.hourlyProfile.clone();
        }
    }

    /** Linear interpolation between the two hourly buckets around a minute-of-day. */
    private static double interpolatedBaseline(EdgeState s, double minuteOfDay) {
        double wrapped = ((minuteOfDay % 1440) + 1440) % 1440;
        int hourA = (int) (wrapped / 60) % 24;
        int hourB = (hourA + 1) % 24;
        double frac = (wrapped % 60) / 60.0;
        return s.hourlyProfile[hourA] * (1 - frac) + s.hourlyProfile[hourB] * frac;
    }
}
