package com.mapnavigator.web.dto;

import java.util.List;

import com.mapnavigator.core.AStarNavigator;

/**
 * One computed route as exposed by the API.
 *
 * {@code etaConfidenceMinutes} is the half-width of an ~80% confidence band
 * around the ETA, derived from the traffic predictor's per-segment forecast
 * uncertainty at the time each segment would be driven.
 */
public record RouteView(String from, String to, String profile, boolean predictive,
                        double departInMinutes, List<NodeView> path,
                        List<AStarNavigator.RouteSegment> segments,
                        double totalDistanceKm, double etaMinutes,
                        double etaConfidenceMinutes) {}
