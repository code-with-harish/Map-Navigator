package com.mapnavigator.web.dto;

/**
 * Body of POST /api/incidents.
 *
 * {@code type} is "accident" or "closure". Severity (extra congestion added
 * by an accident) defaults to 2.0; duration defaults to 30 minutes.
 */
public record IncidentRequest(Integer from, Integer to, String type,
                              Double severity, Double durationMinutes) {}
