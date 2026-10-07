package com.redditclone.media;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

// Runs an external program (ffmpeg / ffprobe) to completion. A seam so VideoProcessingWorker can be tested without
// the real binaries. Throws IOException if the program exits non-zero or outlives `timeout`.
public interface CommandRunner {

    // Returns the program's combined output (at most the last few KB of it).
    String run(List<String> command, Duration timeout) throws IOException, InterruptedException;
}
