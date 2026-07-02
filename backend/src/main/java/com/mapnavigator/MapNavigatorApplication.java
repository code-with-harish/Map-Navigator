package com.mapnavigator;

import java.time.LocalTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mapnavigator.core.AStarNavigator;
import com.mapnavigator.core.Graph;
import com.mapnavigator.core.RoadNetwork;
import com.mapnavigator.traffic.SensorNetwork;
import com.mapnavigator.traffic.TrafficPredictor;
import com.mapnavigator.traffic.TrafficService;
import com.mapnavigator.traffic.WeatherService;

/**
 * Map Navigator entry point.
 *
 * Wires the framework-free core (graph, A*, traffic, weather, sensors,
 * prediction) into Spring beans; the live tick runs in
 * {@link com.mapnavigator.web.SimulationHeartbeat}.
 */
@SpringBootApplication
@EnableScheduling
public class MapNavigatorApplication {

    private static final Logger log = LoggerFactory.getLogger(MapNavigatorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(MapNavigatorApplication.class, args);
    }

    @Bean
    public Graph graph(@Value("${db.enabled:false}") boolean dbEnabled,
                       @Value("${db.url:jdbc:postgresql://localhost:5432/mapnavigator}") String url,
                       @Value("${db.user:postgres}") String user,
                       @Value("${db.password:postgres}") String password) throws Exception {
        Graph graph = dbEnabled ? RoadNetwork.fromDatabase(url, user, password)
                                : RoadNetwork.embedded();
        log.info("Road network loaded from {}: {} nodes, {} directed edges",
                dbEnabled ? "PostgreSQL" : "embedded dataset",
                graph.nodes().size(), graph.edges().size());
        return graph;
    }

    @Bean
    public TrafficPredictor trafficPredictor() {
        return new TrafficPredictor();
    }

    @Bean
    public SensorNetwork sensorNetwork(Graph graph) {
        return new SensorNetwork(graph, System.nanoTime());
    }

    @Bean
    public WeatherService weatherService() {
        return new WeatherService(System.nanoTime());
    }

    @Bean
    public TrafficService trafficService(Graph graph, SensorNetwork sensors,
                                         TrafficPredictor predictor,
                                         @Value("${sim.warmup-days:3}") int warmupDays) {
        TrafficService service = new TrafficService(graph, sensors, predictor, System.nanoTime());
        service.warmUp(warmupDays);       // replay simulated days so predictions start sensible
        service.tick(LocalTime.now());    // and take a first live snapshot immediately
        return service;
    }

    @Bean
    public AStarNavigator navigator(Graph graph) {
        return new AStarNavigator(graph);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(
            @Value("${cors.allowed-origins:*}") String[] allowedOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (allowedOrigins.length == 0) return; // same-origin deployments need no CORS
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins)
                        .allowedMethods("GET", "POST", "DELETE");
            }
        };
    }
}
