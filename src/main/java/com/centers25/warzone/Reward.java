package com.centers25.warzone;

final class Reward {
    private long total;
    private long last;
    private boolean running;

    Reward(long total) {
        if (total < 0) throw new IllegalArgumentException("Negative reward time");
        this.total = total;
    }

    long total(long now) {
        return running ? total + Math.max(0, (now - last) / 1_000_000L) : total;
    }

    void update(long now, boolean eligible) {
        if (running) {
            long elapsed = Math.max(0, (now - last) / 1_000_000L);
            total += elapsed;
            last += elapsed * 1_000_000L;
        } else {
            last = now;
        }
        running = eligible;
    }
}
