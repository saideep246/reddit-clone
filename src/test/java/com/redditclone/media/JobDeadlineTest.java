package com.redditclone.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDeadlineTest {

    private final AtomicLong clock = new AtomicLong(5_000);

    @Test
    void remainingShrinksAsTimePasses() throws IOException {
        JobDeadline deadline = new JobDeadline(Duration.ofMinutes(8), clock::get);
        assertEquals(Duration.ofMinutes(8), deadline.remaining());

        clock.addAndGet(Duration.ofMinutes(3).toNanos());
        assertEquals(Duration.ofMinutes(5), deadline.remaining());
    }

    @Test
    void throwsOnceTheBudgetIsSpentAndKeepsThrowing() {
        JobDeadline deadline = new JobDeadline(Duration.ofMinutes(8), clock::get);
        clock.addAndGet(Duration.ofMinutes(8).toNanos());

        IOException exactly = assertThrows(IOException.class, deadline::remaining);
        assertTrue(exactly.getMessage().contains("8-minute time limit"), exactly.getMessage());

        clock.addAndGet(Duration.ofMinutes(5).toNanos());
        assertThrows(IOException.class, deadline::remaining);
    }

    @Test
    void oneNanosecondLeftIsStillTime() throws IOException {
        JobDeadline deadline = new JobDeadline(Duration.ofMinutes(1), clock::get);
        clock.addAndGet(Duration.ofMinutes(1).toNanos() - 1);
        assertEquals(Duration.ofNanos(1), deadline.remaining());
    }
}
