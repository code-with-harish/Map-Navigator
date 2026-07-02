package com.mapnavigator;

import java.util.Random;

/**
 * Weather feed affecting routing speeds.
 *
 * Runs as a simple Markov chain over weather states (weather is "sticky":
 * it usually stays the same and transitions gradually). Each state carries a
 * speed factor applied network-wide by the router - rain slows everything
 * down, so ETAs and even chosen routes can change with the weather.
 *
 * To plug in a real provider (e.g. Open-Meteo or OpenWeatherMap), replace
 * {@link #tick()} with an HTTP poll and map the provider's condition codes
 * onto {@link Condition}; nothing else in the system needs to change.
 */
public class WeatherService {

    public enum Condition {
        CLEAR(1.00, "Clear"),
        CLOUDY(0.97, "Cloudy"),
        LIGHT_RAIN(0.85, "Light rain"),
        HEAVY_RAIN(0.70, "Heavy rain"),
        FOG(0.75, "Fog");

        public final double speedFactor;
        public final String label;
        Condition(double speedFactor, String label) {
            this.speedFactor = speedFactor;
            this.label = label;
        }
    }

    public record Weather(Condition condition, double temperatureC, double speedFactor) {}

    private final Random random;
    private volatile Condition current = Condition.CLEAR;
    private volatile double temperatureC = 26.0;

    public WeatherService(long seed) {
        this.random = new Random(seed);
    }

    /** Advances the weather simulation one step (called on a schedule). */
    public synchronized void tick() {
        double r = random.nextDouble();
        current = switch (current) {
            case CLEAR      -> r < 0.90 ? Condition.CLEAR      : (r < 0.97 ? Condition.CLOUDY : Condition.FOG);
            case CLOUDY     -> r < 0.60 ? Condition.CLOUDY     : (r < 0.85 ? Condition.CLEAR  : Condition.LIGHT_RAIN);
            case LIGHT_RAIN -> r < 0.55 ? Condition.LIGHT_RAIN : (r < 0.80 ? Condition.CLOUDY : Condition.HEAVY_RAIN);
            case HEAVY_RAIN -> r < 0.50 ? Condition.HEAVY_RAIN : Condition.LIGHT_RAIN;
            case FOG        -> r < 0.60 ? Condition.FOG        : Condition.CLEAR;
        };
        temperatureC = clamp(temperatureC + (random.nextDouble() - 0.5) * 0.8
                + (current == Condition.HEAVY_RAIN ? -0.3 : 0.05), 16, 38);
    }

    public Weather current() {
        return new Weather(current, Math.round(temperatureC * 10) / 10.0, current.speedFactor);
    }

    /** Network-wide speed multiplier used by the router. */
    public double speedFactor() {
        return current.speedFactor;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
