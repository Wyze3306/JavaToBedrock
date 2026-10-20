package io.github.wyze3306.jtb.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ThrottledQueueTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    void releasesAtTheConfiguredRateWithoutDropping() {
        long now = 0;
        ThrottledQueue<Integer> queue = new ThrottledQueue<>(2, 600 * MS, 64, now);
        List<Integer> sent = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            queue.offer(i);
        }
        queue.drain(now, sent::add);
        assertEquals(List.of(0), sent, "one token at start");
        queue.drain(now + 599 * MS, sent::add);
        assertEquals(1, sent.size());
        queue.drain(now + 600 * MS, sent::add);
        assertEquals(List.of(0, 1), sent);
        queue.drain(now + 3000 * MS, sent::add);
        assertEquals(List.of(0, 1, 2, 3), sent, "burst capped at two");
        queue.drain(now + 3600 * MS, sent::add);
        assertEquals(List.of(0, 1, 2, 3, 4), sent);
    }

    @Test
    void neverExceedsTheServerLimitOverOneSecond() {
        // PowerNukkitX allows 2 chat messages per second; check any 1 s window of a long flood.
        ThrottledQueue<Long> queue = new ThrottledQueue<>(2, 1000 * MS, 1000, 0);
        List<Long> times = new ArrayList<>();
        for (long i = 0; i < 100; i++) {
            queue.offer(i);
        }
        for (long t = 0; t < 30_000 * MS; t += 50 * MS) {
            long at = t;
            queue.drain(t, item -> times.add(at));
        }
        for (int i = 0; i + 2 < times.size(); i++) {
            // three consecutive sends always span more than one second
            assertFalse(times.get(i + 2) - times.get(i) < 1000 * MS, "window at " + i);
        }
    }

    @Test
    void refusesBeyondCapacity() {
        ThrottledQueue<Integer> queue = new ThrottledQueue<>(1, MS, 2, 0);
        queue.offer(1);
        queue.offer(2);
        assertFalse(queue.offer(3));
    }
}
