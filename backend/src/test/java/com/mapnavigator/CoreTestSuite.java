package com.mapnavigator;

/**
 * Entry point for the framework-free core tests.
 *
 * Compile and run with nothing but a JDK:
 *
 *   cd backend
 *   javac -d target/core-tests \
 *       src/main/java/com/mapnavigator/core/*.java \
 *       src/main/java/com/mapnavigator/traffic/*.java \
 *       src/test/java/com/mapnavigator/*.java
 *   java -cp target/core-tests com.mapnavigator.CoreTestSuite
 */
public final class CoreTestSuite {

    private CoreTestSuite() {}

    public static void main(String[] args) {
        RoutingTests.run();
        TrafficTests.run();
        PredictionTests.run();
        TestKit.finish();
    }
}
