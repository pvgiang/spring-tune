package io.github.winkytek.springtune.loadtests;

/**
 * The run's base seed: {@code -Dspringtune.it.seed} to replay a run, otherwise derived from the clock. Logged
 * once and written to every result file; each load generator derives its own seed from it and its name.
 */
final class Seeds {

    static final long BASE;

    static {
        String configured = System.getProperty("springtune.it.seed");
        BASE = configured == null || configured.isBlank() ? System.currentTimeMillis() : Long.parseLong(configured);
        System.out.println("[loadtests] base seed " + BASE + " (replay with -Dspringtune.it.seed=" + BASE + ")");
    }

    private Seeds() {
    }

    static long derive(long salt) {
        return BASE * 31 + salt;
    }
}
