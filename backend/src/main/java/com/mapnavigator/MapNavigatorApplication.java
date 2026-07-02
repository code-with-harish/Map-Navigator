package com.mapnavigator;

import java.time.LocalTime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Map Navigator - Spring Boot entry point.
 *
 * Wires the framework-free core (graph, A*, traffic, weather, IoT, ML) into
 * Spring beans, schedules the live simulation ticks, and opens CORS so the
 * static frontend (frontend/index.html) can call the API from anywhere.
 */
@SpringBootApplication
@EnableScheduling
public class MapNavigatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MapNavigatorApplication.class, args);
    }

    @Bean
    public Graph graph(@Value("${db.enabled:false}") boolean dbEnabled,
                       @Value("${db.url:jdbc:postgresql://localhost:5432/mapnavigator}") String url,
                       @Value("${db.user:postgres}") String user,
                       @Value("${db.password:postgres}") String password) throws Exception {
        return dbEnabled ? RoadNetwork.fromDatabase(url, user, password) : RoadNetwork.embedded();
    }

    @Bean
    public MLModel mlModel() {
        return new MLModel();
    }

    @Bean
    public IoTIntegration iotIntegration(Graph graph) {
        return new IoTIntegration(graph, System.nanoTime());
    }

    @Bean
    public WeatherService weatherService() {
        return new WeatherService(System.nanoTime());
    }

    @Bean
    public TrafficDataService trafficDataService(Graph graph, IoTIntegration iot, MLModel ml) {
        TrafficDataService svc = new TrafficDataService(graph, iot, ml, System.nanoTime());
        svc.warmUp(3);              // replay 3 simulated days so predictions start sensible
        svc.tick(LocalTime.now());  // and take a first live snapshot immediately
        return svc;
    }

    @Bean
    public AStarNavigator navigator(Graph graph) {
        return new AStarNavigator(graph);
    }

    /** Live simulation heartbeat. */
    @Bean
    public Object scheduler(TrafficDataService traffic, WeatherService weather) {
        return new Object() {
            @Scheduled(fixedRateString = "${sim.tick-ms:5000}")
            public void tick() {
                weather.tick();
                traffic.tick(LocalTime.now());
            }
        };
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("*");
            }
        };
    }
}
