package com.mapnavigator.web;

import java.time.LocalTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mapnavigator.traffic.TrafficService;
import com.mapnavigator.traffic.WeatherService;

/**
 * Advances the live simulation on a fixed schedule. The tick interval is
 * configurable via {@code sim.tick-ms} (default 5s).
 */
@Component
public class SimulationHeartbeat {

    private final TrafficService traffic;
    private final WeatherService weather;

    public SimulationHeartbeat(TrafficService traffic, WeatherService weather) {
        this.traffic = traffic;
        this.weather = weather;
    }

    @Scheduled(fixedRateString = "${sim.tick-ms:5000}")
    public void tick() {
        weather.tick();
        traffic.tick(LocalTime.now());
    }
}
