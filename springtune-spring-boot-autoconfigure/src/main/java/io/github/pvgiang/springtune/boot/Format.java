package io.github.pvgiang.springtune.boot;

import java.time.Duration;

final class Format {

    private Format() {
    }

    static String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    static String millis(double ms) {
        return Math.round(ms) + " ms";
    }

    static String duration(Duration duration) {
        return duration.toString().substring(2).toLowerCase(java.util.Locale.ROOT);
    }

    static int step(int current, double step) {
        return current + Math.max(1, (int) Math.ceil(current * step));
    }
}
