package com.redditclone.media;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

// One time budget for a whole media job (download, every ffmpeg/ffprobe call, every upload). Each step asks for the time
// that is left and is bounded by it, and no step starts once the budget is gone, so the total cannot run past it by more
// than the cost of the final database update. Per-command timeouts alone cannot give that guarantee: they add up.
final class JobDeadline {

    private final long deadlineNanos;
    private final LongSupplier nanoClock;
    private final long budgetMinutes;

    JobDeadline(Duration budget, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.budgetMinutes = budget.toMinutes();
        this.deadlineNanos = nanoClock.getAsLong() + budget.toNanos();
    }

    // The time left for the next step. Throws (so the job fails and is retried or marked failed in the usual way) once
    // there is none.
    Duration remaining() throws IOException {
        long left = deadlineNanos - nanoClock.getAsLong();
        if (left <= 0) {
            throw new IOException("video job exceeded its " + budgetMinutes + "-minute time limit");
        }
        return Duration.ofNanos(left);
    }
}
