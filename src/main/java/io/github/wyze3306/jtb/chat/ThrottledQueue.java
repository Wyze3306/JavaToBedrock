package io.github.wyze3306.jtb.chat;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * A FIFO released through a token bucket, so the server's rate limiter never sees more than it
 * accepts: items wait instead of being dropped by the server (or getting the player kicked).
 * Not thread-safe: used on the session event loop.
 *
 * @param <T> queued item
 */
final class ThrottledQueue<T> {

    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final int burst;
    private final long intervalNanos;
    private final int maxQueued;
    private double tokens;
    private long refilledAt;

    /**
     * @param burst         items that may leave at once after a pause
     * @param intervalNanos time to earn one more item
     * @param maxQueued     items kept waiting at most (beyond, {@link #offer} refuses)
     * @param now           current {@link System#nanoTime()}; the bucket starts with one token
     */
    ThrottledQueue(int burst, long intervalNanos, int maxQueued, long now) {
        this.burst = burst;
        this.intervalNanos = intervalNanos;
        this.maxQueued = maxQueued;
        this.tokens = 1;
        this.refilledAt = now;
    }

    /** Queues an item; false when the queue is full. */
    boolean offer(T item) {
        if (this.queue.size() >= this.maxQueued) {
            return false;
        }
        this.queue.add(item);
        return true;
    }

    /** Releases as many queued items as the bucket allows, in order. */
    void drain(long now, Consumer<T> sink) {
        refill(now);
        while (this.tokens >= 1 && !this.queue.isEmpty()) {
            this.tokens -= 1;
            sink.accept(this.queue.poll());
        }
    }

    int size() {
        return this.queue.size();
    }

    void clear() {
        this.queue.clear();
    }

    private void refill(long now) {
        long elapsed = now - this.refilledAt;
        if (elapsed > 0) {
            this.tokens = Math.min(this.burst, this.tokens + (double) elapsed / this.intervalNanos);
            this.refilledAt = now;
        }
    }
}
