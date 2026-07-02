package com.mapnavigator.web.dto;

/** Uniform error body for all non-2xx API responses. */
public record ErrorView(String error) {}
