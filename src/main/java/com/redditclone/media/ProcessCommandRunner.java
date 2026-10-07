package com.redditclone.media;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessCommandRunner implements CommandRunner {

    private static final int MAX_OUTPUT_CHARS = 8 * 1024;

    @Override
    public String run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        // stderr is merged into stdout and drained on its own thread, so a chatty child can never block on a full pipe
        // buffer. Only the tail is kept: ffmpeg's progress output used to be read into memory in full and thrown away.
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Tail tail = new Tail(MAX_OUTPUT_CHARS);
        Thread drain = new Thread(() -> tail.readFrom(process.getInputStream()), "command-output-drain");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException(programName(command) + " timed out after " + timeout.toSeconds() + " seconds");
            }
            drain.join(2_000);
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IOException(programName(command) + " exited with status " + exit + tail.lastLineSuffix());
            }
            return tail.toString();
        } finally {
            // Covers interruption and any other early exit: never leave an orphaned ffmpeg running (and writing to a
            // directory that is about to be deleted).
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // The program being run, skipping a leading `nice -n <level>` wrapper, so errors keep saying "ffmpeg exited with
    // status 137" rather than "nice exited ...".
    static String programName(List<String> command) {
        if (command.size() > 3 && "nice".equals(command.getFirst())) {
            return command.get(3);
        }
        return command.getFirst();
    }

    // Keeps only the last `max` characters read.
    static final class Tail {
        private final int max;
        private final StringBuilder buffer = new StringBuilder();

        Tail(int max) {
            this.max = max;
        }

        void readFrom(InputStream in) {
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // the process ended or was killed; whatever was read so far is all we keep
            }
        }

        synchronized void append(String text) {
            buffer.append(text);
            if (buffer.length() > max) {
                buffer.delete(0, buffer.length() - max);
            }
        }

        synchronized String lastLineSuffix() {
            String[] lines = buffer.toString().strip().split("\\R");
            String last = lines.length == 0 ? "" : lines[lines.length - 1].strip();
            if (last.isEmpty()) {
                return "";
            }
            return ": " + (last.length() > 200 ? last.substring(0, 200) : last);
        }

        @Override
        public synchronized String toString() {
            return buffer.toString();
        }
    }
}
