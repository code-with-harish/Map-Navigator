package com.mapnavigator;

/**
 * Minimal assertion harness for the framework-free core tests.
 *
 * The routing and simulation core deliberately has zero dependencies, so its
 * tests compile and run with nothing but a JDK (no Maven, no network). See
 * CoreTestSuite for the entry point and the README for the one-line command.
 */
final class TestKit {

    private static int passed = 0;
    private static int failed = 0;

    private TestKit() {}

    static void section(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    static void check(boolean condition, String message) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + message);
        } else {
            failed++;
            System.out.println("  FAIL  " + message);
        }
    }

    static void checkClose(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance,
                message + " (expected " + expected + " ± " + tolerance + ", got " + actual + ")");
    }

    static void note(String message) {
        System.out.println("        " + message);
    }

    /** Prints the summary and exits non-zero on any failure (for CI). */
    static void finish() {
        System.out.println();
        System.out.println(failed == 0
                ? "ALL " + passed + " CHECKS PASSED"
                : failed + " OF " + (passed + failed) + " CHECKS FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
