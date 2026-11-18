package com.centers25.warzone;

final class Playtime {
    private long remaining;
    private long last;
    private boolean running;

    Playtime(long remaining) {
        if (remaining < 0) throw new IllegalArgumentException("Negative remaining playtime");
        this.remaining = remaining;
    }

    long remaining(long now) {
        return running ? Math.max(0, remaining - elapsed(now)) : remaining;
    }

    void update(long now, boolean eligible) {
        long elapsed = running ? elapsed(now) : 0;
        remaining = Math.max(0, remaining - elapsed);
        last = running && eligible ? last + elapsed * 1_000_000L : now;
        running = eligible && remaining > 0;
    }

    void adjust(long now, long delta) {
        update(now, running);
        remaining = delta >= 0
                ? (remaining > Long.MAX_VALUE - delta ? Long.MAX_VALUE : remaining + delta)
                : Math.max(0, remaining + delta);
        running &= remaining > 0;
    }

    private long elapsed(long now) {
        return Math.max(0, (now - last) / 1_000_000L);
    }
}
