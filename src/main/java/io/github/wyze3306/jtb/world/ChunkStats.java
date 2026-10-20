package io.github.wyze3306.jtb.world;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Timings of the chunk pipeline of a session, reported once in the log when the first view
 * finished loading. Thread safe (decode and light run on workers).
 */
final class ChunkStats {

    private static final int REPORT_AFTER = 49;

    private final long start = System.nanoTime();
    private final LongAdder decoded = new LongAdder();
    private final LongAdder decodeNanos = new LongAdder();
    private final LongAdder lit = new LongAdder();
    private final LongAdder lightNanos = new LongAdder();
    private final LongAdder built = new LongAdder();
    private final LongAdder buildNanos = new LongAdder();
    private int sent;
    private boolean reported;

    void decoded(long nanos) {
        this.decoded.increment();
        this.decodeNanos.add(nanos);
    }

    void lit(long nanos) {
        this.lit.increment();
        this.lightNanos.add(nanos);
    }

    void built(long nanos) {
        this.built.increment();
        this.buildNanos.add(nanos);
    }

    void sent() {
        this.sent++;
    }

    /** True once, the first time the pipeline is idle after the initial view was sent. */
    boolean shouldReport(boolean idle) {
        if (this.reported || !idle || this.sent <= REPORT_AFTER) {
            return false;
        }
        this.reported = true;
        return true;
    }

    String report() {
        return String.format("%d chunks sent in %d ms (per chunk: decode %.2f ms, light %.2f ms, encode %.2f ms, worker time)",
                this.sent, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.start),
                average(this.decodeNanos, this.decoded), average(this.lightNanos, this.lit), average(this.buildNanos, this.built));
    }

    private static double average(LongAdder nanos, LongAdder count) {
        long n = count.sum();
        return n == 0 ? 0 : nanos.sum() / 1e6 / n;
    }
}
