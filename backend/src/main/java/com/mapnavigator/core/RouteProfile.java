package com.mapnavigator.core;

/**
 * Cost model a routing query optimises for.
 *
 * Each profile defines the cost of traversing one edge and a per-km lower
 * bound used to keep the A* heuristic admissible and consistent (the
 * heuristic is always haversine distance to the goal times that bound).
 */
public enum RouteProfile {

    /** Minimise predicted travel time. */
    FASTEST {
        @Override
        public double edgeCost(Graph.Edge e, double congestion, double weatherFactor) {
            return e.travelTimeMinutes(congestion, weatherFactor);
        }

        @Override
        public double heuristicPerKm(Graph g) {
            // minutes per km at the network's best speed in clear weather:
            // no edge can be traversed faster, so this never overestimates
            return 60.0 / g.maxSpeedKmh();
        }
    },

    /** Minimise driven distance. */
    SHORTEST {
        @Override
        public double edgeCost(Graph.Edge e, double congestion, double weatherFactor) {
            return e.distanceKm;
        }

        @Override
        public double heuristicPerKm(Graph g) {
            return 1.0; // straight-line distance never exceeds road distance
        }
    },

    /**
     * Minimise a fuel-consumption proxy.
     *
     * Consumption per km is modelled as a parabola around an efficient
     * cruising speed (~60 km/h), inflated by stop-and-go traffic. The factor
     * is always >= 1, so plain haversine distance is an admissible heuristic.
     */
    ECO {
        private static final double EFFICIENT_SPEED_KMH = 60.0;

        @Override
        public double edgeCost(Graph.Edge e, double congestion, double weatherFactor) {
            double cruise = e.speedLimitKmh * weatherFactor;
            double speedFactor = 1.0 + Math.pow((cruise - EFFICIENT_SPEED_KMH) / 75.0, 2);
            double stopAndGo = 1.0 + 0.35 * (congestion - 1.0);
            return e.distanceKm * speedFactor * stopAndGo;
        }

        @Override
        public double heuristicPerKm(Graph g) {
            return 1.0;
        }
    };

    public abstract double edgeCost(Graph.Edge e, double congestion, double weatherFactor);

    public abstract double heuristicPerKm(Graph g);

    /** Case-insensitive parse with FASTEST as the default. */
    public static RouteProfile parse(String value) {
        if (value == null || value.isBlank()) return FASTEST;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown profile '" + value + "' (expected fastest, shortest, or eco)");
        }
    }
}
