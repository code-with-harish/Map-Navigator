package com.mapnavigator.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mapnavigator.core.Graph;
import com.mapnavigator.web.dto.ErrorView;

/**
 * Per-user saved places and recent route history.
 *
 * Kept in memory: collections are lock-free concurrent types since saves and
 * reads arrive on different request threads. The PostgreSQL schema for
 * persisting these tables ships in database/PostgresDBSetup.sql, so a
 * JDBC-backed swap-in stays straightforward.
 *
 *   GET    /api/users/{user}/places        list saved places
 *   POST   /api/users/{user}/places        save a place {label, nodeId}
 *   DELETE /api/users/{user}/places/{id}   remove a saved place
 *   GET    /api/users/{user}/history       recent routes (newest first)
 *   POST   /api/users/{user}/history       record a route {fromNodeId, toNodeId, etaMinutes}
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
    private static final int MAX_PLACES = 50;
    private static final int MAX_LABEL_LENGTH = 40;
    private static final int MAX_USERNAME_LENGTH = 64;

    private final Graph graph;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<String, List<SavedPlace>> places = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<RouteHistoryEntry>> history =
            new ConcurrentHashMap<>();

    public UserController(Graph graph) {
        this.graph = graph;
    }

    @GetMapping("/places")
    public ResponseEntity<?> listPlaces(@PathVariable String user) {
        if (invalidUser(user)) return badUser();
        return ResponseEntity.ok(places.getOrDefault(user, List.of()));
    }

    @PostMapping("/places")
    public ResponseEntity<?> savePlace(@PathVariable String user,
                                       @RequestBody Map<String, Object> body) {
        if (invalidUser(user)) return badUser();
        Object labelValue = body.get("label");
        if (!(body.get("nodeId") instanceof Number nodeValue) || labelValue == null) {
            return badRequest("label and nodeId are required");
        }
        String label = labelValue.toString().strip();
        if (label.isEmpty()) return badRequest("label must not be blank");
        if (label.length() > MAX_LABEL_LENGTH) label = label.substring(0, MAX_LABEL_LENGTH);

        int nodeId = nodeValue.intValue();
        Graph.Node node = graph.node(nodeId);
        if (node == null) return badRequest("Unknown nodeId " + nodeId);

        List<SavedPlace> list = places.computeIfAbsent(user, k -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_PLACES) {
            return badRequest("At most " + MAX_PLACES + " saved places per user");
        }
        SavedPlace place = new SavedPlace(ids.getAndIncrement(), label,
                nodeId, node.name(), node.lat(), node.lon());
        list.add(place);
        return ResponseEntity.status(HttpStatus.CREATED).body(place);
    }

    @DeleteMapping("/places/{id}")
    public ResponseEntity<?> deletePlace(@PathVariable String user, @PathVariable long id) {
        List<SavedPlace> list = places.get(user);
        boolean removed = list != null && list.removeIf(p -> p.id() == id);
        return removed
                ? ResponseEntity.ok(Map.of("deleted", id))
                : ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorView("No saved place with id " + id));
    }

    @GetMapping("/history")
    public ResponseEntity<?> listHistory(@PathVariable String user,
                                         @RequestParam(defaultValue = "10") int limit) {
        if (invalidUser(user)) return badUser();
        ConcurrentLinkedDeque<RouteHistoryEntry> deque = history.get(user);
        if (deque == null) return ResponseEntity.ok(List.of());
        List<RouteHistoryEntry> out = new ArrayList<>();
        int max = Math.min(Math.max(1, limit), MAX_HISTORY);
        for (RouteHistoryEntry entry : deque) { // weakly consistent, safe under writes
            out.add(entry);
            if (out.size() >= max) break;
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/history")
    public ResponseEntity<?> recordRoute(@PathVariable String user,
                                         @RequestBody Map<String, Object> body) {
        if (invalidUser(user)) return badUser();
        if (!(body.get("fromNodeId") instanceof Number fromValue)
                || !(body.get("toNodeId") instanceof Number toValue)) {
            return badRequest("fromNodeId and toNodeId are required");
        }
        Graph.Node from = graph.node(fromValue.intValue());
        Graph.Node to = graph.node(toValue.intValue());
        if (from == null || to == null) return badRequest("Unknown node id");

        double eta = body.get("etaMinutes") instanceof Number n ? n.doubleValue() : 0;
        RouteHistoryEntry entry = new RouteHistoryEntry(ids.getAndIncrement(),
                from.id(), from.name(), to.id(), to.name(), eta, System.currentTimeMillis());
        ConcurrentLinkedDeque<RouteHistoryEntry> deque =
                history.computeIfAbsent(user, k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(entry);
        while (deque.size() > MAX_HISTORY) deque.pollLast();
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    private static boolean invalidUser(String user) {
        return user == null || user.isBlank() || user.length() > MAX_USERNAME_LENGTH;
    }

    private static ResponseEntity<ErrorView> badUser() {
        return badRequest("User name must be 1-" + MAX_USERNAME_LENGTH + " characters");
    }

    private static ResponseEntity<ErrorView> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ErrorView(message));
    }
}
