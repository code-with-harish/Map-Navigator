package com.mapnavigator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-user saved places and recent route history.
 *
 * Kept in memory for simplicity; the schema for persisting these tables in
 * PostgreSQL is included in database/PostgresDBSetup.sql, so swapping this
 * for a JDBC-backed implementation is straightforward.
 *
 *   GET    /api/users/{user}/places            - list saved places
 *   POST   /api/users/{user}/places            - save a place {label, nodeId}
 *   DELETE /api/users/{user}/places/{id}       - remove a saved place
 *   GET    /api/users/{user}/history           - recent routes (newest first)
 *   POST   /api/users/{user}/history           - record a route {fromNodeId, toNodeId, etaMinutes}
 */
@RestController
@RequestMapping("/api/users/{user}")
public class UserController {

    public record SavedPlace(long id, String label, int nodeId, String nodeName,
                             double lat, double lon) {}
    public record RouteHistoryEntry(long id, int fromNodeId, String fromName,
                                    int toNodeId, String toName,
                                    double etaMinutes, long timestampMs) {}

    private static final int MAX_HISTORY = 25;

    private final Graph graph;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<String, List<SavedPlace>> places = new ConcurrentHashMap<>();
    private final Map<String, Deque<RouteHistoryEntry>> history = new ConcurrentHashMap<>();

    public UserController(Graph graph) {
        this.graph = graph;
    }

    @GetMapping("/places")
    public List<SavedPlace> listPlaces(@PathVariable String user) {
        return places.getOrDefault(user, List.of());
    }

    @PostMapping("/places")
    public ResponseEntity<?> savePlace(@PathVariable String user,
                                       @RequestBody Map<String, Object> body) {
        Object labelObj = body.get("label");
        Object nodeObj = body.get("nodeId");
        if (labelObj == null || nodeObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "label and nodeId are required"));
        }
        int nodeId = ((Number) nodeObj).intValue();
        Graph.Node node = graph.node(nodeId);
        if (node == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown nodeId " + nodeId));
        }
        SavedPlace place = new SavedPlace(ids.getAndIncrement(), labelObj.toString(),
                nodeId, node.name(), node.lat(), node.lon());
        places.computeIfAbsent(user, k -> new ArrayList<>()).add(place);
        return ResponseEntity.ok(place);
    }

    @DeleteMapping("/places/{id}")
    public ResponseEntity<?> deletePlace(@PathVariable String user, @PathVariable long id) {
        List<SavedPlace> list = places.get(user);
        boolean removed = list != null && list.removeIf(p -> p.id() == id);
        return removed ? ResponseEntity.ok(Map.of("deleted", id))
                       : ResponseEntity.status(404).body(Map.of("error", "Not found"));
    }

    @GetMapping("/history")
    public List<RouteHistoryEntry> listHistory(@PathVariable String user,
                                               @RequestParam(defaultValue = "10") int limit) {
        Deque<RouteHistoryEntry> deque = history.getOrDefault(user, new ArrayDeque<>());
        return deque.stream().limit(Math.max(1, limit)).toList();
    }

    @PostMapping("/history")
    public ResponseEntity<?> recordRoute(@PathVariable String user,
                                         @RequestBody Map<String, Object> body) {
        Object fromObj = body.get("fromNodeId");
        Object toObj = body.get("toNodeId");
        if (fromObj == null || toObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "fromNodeId and toNodeId are required"));
        }
        Graph.Node from = graph.node(((Number) fromObj).intValue());
        Graph.Node to = graph.node(((Number) toObj).intValue());
        if (from == null || to == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown node id"));
        }
        double eta = body.get("etaMinutes") instanceof Number n ? n.doubleValue() : 0;
        RouteHistoryEntry entry = new RouteHistoryEntry(ids.getAndIncrement(),
                from.id(), from.name(), to.id(), to.name(), eta, System.currentTimeMillis());
        Deque<RouteHistoryEntry> deque =
                history.computeIfAbsent(user, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(entry);
            while (deque.size() > MAX_HISTORY) deque.removeLast();
        }
        return ResponseEntity.ok(entry);
    }
}
