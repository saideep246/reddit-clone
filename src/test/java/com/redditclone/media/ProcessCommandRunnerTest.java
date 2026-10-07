package com.redditclone.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs real (POSIX sh) processes: these tests cover how external programs are supervised, not ffmpeg itself.
class ProcessCommandRunnerTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    void returnsTheProgramOutput() throws Exception {
        assertEquals("hello", runner.run(List.of("sh", "-c", "echo hello"), Duration.ofSeconds(10)).trim());
    }

    @Test
    void aNonZeroExitBecomesAnIOExceptionWithTheStatusAndLastErrorLine() {
        IOException e = assertThrows(IOException.class,
                () -> runner.run(List.of("sh", "-c", "echo first >&2; echo boom >&2; exit 137"), Duration.ofSeconds(10)));
        assertTrue(e.getMessage().startsWith("sh exited with status 137"), e.getMessage());
        assertTrue(e.getMessage().endsWith(": boom"), e.getMessage());
    }

    @Test
    void theNiceWrapperIsNotNamedInErrors() {
        IOException e = assertThrows(IOException.class,
                () -> runner.run(List.of("nice", "-n", "10", "sh", "-c", "exit 3"), Duration.ofSeconds(10)));
        assertTrue(e.getMessage().startsWith("sh exited with status 3"), e.getMessage());
    }

    @Test
    void aCommandThatRunsTooLongIsKilledAndReported() {
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class,
                () -> runner.run(List.of("sh", "-c", "sleep 30"), Duration.ofSeconds(1)));
        assertTrue(e.getMessage().contains("timed out after 1 seconds"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10, "must not wait for the command to finish");
    }

    @Test
    void hugeOutputIsDrainedButOnlyTheTailIsKept() throws Exception {
        String out = runner.run(List.of("sh", "-c", "yes x | head -c 5000000"), Duration.ofSeconds(20));
        assertTrue(out.length() <= 8 * 1024, "kept " + out.length() + " characters");
        assertFalse(out.isEmpty());
    }

    @Test
    void interruptingTheCallerKillsTheProcess(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("pid");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                runner.run(List.of("sh", "-c", "echo $$ > " + pidFile + "; sleep 30"), Duration.ofMinutes(1));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        caller.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while ((!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process should be running");

        caller.interrupt();
        caller.join(10_000);

        assertTrue(thrown.get() instanceof InterruptedException, String.valueOf(thrown.get()));
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "interrupt must not orphan the process");
    }
}
