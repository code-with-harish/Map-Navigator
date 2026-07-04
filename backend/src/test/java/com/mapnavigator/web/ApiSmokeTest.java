package com.mapnavigator.web;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end smoke test through the full Spring stack. The framework-free
 * routing and simulation logic has its own exhaustive suite (CoreTestSuite);
 * this covers the wiring: context startup, JSON shapes, and error mapping.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void networkReturnsNodesAndEdges() throws Exception {
        mvc.perform(get("/api/network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(24)))
                .andExpect(jsonPath("$.edges", hasSize(37)));
    }

    @Test
    void routeByNodeIdsIncludesEtaAndConfidence() throws Exception {
        mvc.perform(get("/api/route").param("from", "5").param("to", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etaMinutes").value(greaterThan(0.0)))
                .andExpect(jsonPath("$.etaConfidenceMinutes").value(greaterThan(0.0)))
                .andExpect(jsonPath("$.path[0].name").value("Majestic"));
    }

    @Test
    void alternativesAndProfilesAreAccepted() throws Exception {
        mvc.perform(get("/api/route")
                        .param("from", "5").param("to", "9")
                        .param("profile", "eco").param("alternatives", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("eco"))
                .andExpect(jsonPath("$.alternatives").isArray());
    }

    @Test
    void unknownProfileIsA400WithJsonError() throws Exception {
        mvc.perform(get("/api/route")
                        .param("from", "5").param("to", "9").param("profile", "teleport"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void malformedParameterIsA400NotA500() throws Exception {
        mvc.perform(get("/api/route").param("from", "five").param("to", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void incidentLifecycle() throws Exception {
        mvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":1,\"to\":2,\"type\":\"closure\",\"durationMinutes\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CLOSURE"));
        mvc.perform(get("/api/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roadName").value("MG Road"));
    }

    @Test
    void savedPlacesRoundTrip() throws Exception {
        mvc.perform(post("/api/users/smoke/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Work\",\"nodeId\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeName").value("Indiranagar"));
        mvc.perform(get("/api/users/smoke/places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("Work"));
        
    }
}
