package com.mapnavigator.web.dto;

/** A map node as exposed by the API. */
public record NodeView(int id, String name, double lat, double lon) {}
