package com.redditclone.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoProcessingWorkerTest {

    private MediaService mediaService;
    private StorageService storage;
    private FakeCommandRunner runner;
    private VideoProcessingWorker worker;
    // media id -> the temp directory the worker created for it (recorded when the download happens)
    private final List<Path> jobDirs = Collections.synchronizedList(new ArrayList<>());
    private final Deque<ClaimedMedia> queue = new ConcurrentLinkedDeque<>();
    // The worker's deadline clock: tests move time by hand instead of waiting.
    private final AtomicLong nanoClock = new AtomicLong(1_000_000_000_000L);

    @BeforeEach
    void setUp() throws IOException {
        mediaService = mock(MediaService.class);
        storage = mock(StorageService.class);
        runner = new FakeCommandRunner();
        worker = new VideoProcessingWorker(mediaService, storage, runner, 2, 10, 8, nanoClock::get);

        doAnswer(inv -> {
            Path target = inv.getArgument(1);
            jobDirs.add(target.getParent());
            Files.writeString(target, "original video bytes");
            return null;
        }).when(storage).downloadToFile(anyString(), any(Path.class), any(Duration.class));
        // like the real upload, the file must exist at the moment it is sent
        doAnswer(inv -> {
            assertTrue(Files.exists(inv.<Path>getArgument(1)), "upload source must exist: " + inv.getArgument(1));
            return null;
        }).when(storage).putFile(anyString(), any(Path.class), anyString(), any(Duration.class));
        when(mediaService.claimNextUploaded(eq("video"), anyInt())).thenAnswer(inv -> {
            ClaimedMedia next = queue.pollFirst();
            return next == null ? List.of() : List.of(next);
        });
    }

    @AfterEach
    void tearDown() {
        runner.release();
        worker.shutdown();
    }

    private static ClaimedMedia media(String key) {
        return new ClaimedMedia(UUID.randomUUID(), UUID.randomUUID(), "video", key, "video/mp4", 1000, 0, null);
    }

    // ---- 1 & 2: no byte[] of the video in the heap ----

    @Test
    void inputAndOutputTravelThroughFilesNeverThroughByteArrays() throws Exception {
        ClaimedMedia m = media("u/1/clip.mp4");

        worker.process(m);

        verify(storage).downloadToFile(eq("u/1/clip.mp4"), any(Path.class), any(Duration.class));
        verify(storage).putFile(eq("u/1/clip.mp4-display.mp4"), any(Path.class), eq("video/mp4"), any(Duration.class));
        verify(storage).putFile(eq("u/1/clip.mp4-thumb.jpg"), any(Path.class), eq("image/jpeg"), any(Duration.class));
        verify(storage, never()).get(anyString());
        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
        verify(mediaService).markReady(eq(m.id()), eq("u/1/clip.mp4-thumb.jpg"), eq("u/1/clip.mp4-display.mp4"),
                eq(1280), eq(720), any());
    }

    // ---- 5: ffmpeg gets the resource options, encoding is unchanged ----

    @Test
    void ffmpegIsLimitedButEncodesExactlyAsBefore() throws Exception {
        worker.process(media("k"));

        List<String> transcode = runner.commands.stream().filter(c -> c.contains("libx264")).findFirst().orElseThrow();
        assertEquals(List.of("nice", "-n", "10", "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error"),
                transcode.subList(0, 8));
        int input = transcode.indexOf("-i");
        assertTrue(transcode.subList(0, input).containsAll(List.of("-threads", "1")), "decoder limited before -i");
        int codec = transcode.indexOf("libx264");
        assertEquals(List.of("-threads", "2", "-preset", "veryfast", "-crf", "26", "-c:a", "aac", "-b:a", "128k"),
                transcode.subList(codec + 1, codec + 11));
        assertTrue(transcode.contains("scale='min(1280,iw)':'-2'"));
        assertEquals("1", transcode.get(transcode.indexOf("-filter_threads") + 1));

        List<String> thumb = runner.commands.stream().filter(c -> c.contains("-frames:v")).findFirst().orElseThrow();
        assertEquals("1", thumb.get(thumb.indexOf("-threads") + 1));
        assertTrue(thumb.containsAll(List.of("-ss", "00:00:00.500", "-frames:v", "1")));
    }

    @Test
    void niceCanBeTurnedOffAndThreadCountIsConfigurable() {
        Path in = Path.of("/t/in"), out = Path.of("/t/out.mp4");
        List<String> c = VideoCommands.transcode(in, out, 1, 0);
        assertEquals("ffmpeg", c.getFirst());
        assertEquals("1", c.get(c.indexOf("libx264") + 2));
    }

    // ---- 6, 7, 8: temp files are always removed ----

    @Test
    void tempDirectoryIsDeletedAfterSuccess() throws Exception {
        worker.process(media("k"));
        assertEquals(1, jobDirs.size());
        assertFalse(Files.exists(jobDirs.getFirst()));
    }

    @Test
    void tempDirectoryIsDeletedAfterFfmpegFailure() {
        runner.failTranscode = new IOException("ffmpeg exited with status 137");
        assertThrows(IOException.class, () -> worker.process(media("k")));
        assertFalse(Files.exists(jobDirs.getFirst()));
        verify(storage, never()).putFile(anyString(), any(Path.class), anyString(), any(Duration.class));
    }

    @Test
    void tempDirectoryIsDeletedAfterUploadFailure() {
        doThrow(new RuntimeException("R2 unavailable")).when(storage).putFile(anyString(), any(Path.class), anyString(), any(Duration.class));
        assertThrows(RuntimeException.class, () -> worker.process(media("k")));
        assertFalse(Files.exists(jobDirs.getFirst()));
        verify(mediaService, never()).markReady(any(), any(), any(), any(), any(), any());
    }

    @Test
    void tempDirectoryIsDeletedAfterDownloadFailure() throws IOException {
        ClaimedMedia m = media("k");
        String prefix = VideoProcessingWorker.TEMP_DIR_PREFIX + m.id();
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        // Positive control: while the (failing) download runs, the job's directory really exists in the temp directory.
        // Without this, "no directory afterwards" would also pass if the worker never created one.
        AtomicInteger existedDuringDownload = new AtomicInteger();
        doAnswer(inv -> {
            existedDuringDownload.set(countDirectoriesWithPrefix(tmp, prefix));
            throw new RuntimeException("download failed");
        }).when(storage).downloadToFile(anyString(), any(Path.class), any(Duration.class));

        assertThrows(RuntimeException.class, () -> worker.process(m));

        assertEquals(1, existedDuringDownload.get(), "the job directory must exist before the download starts");
        assertEquals(0, countDirectoriesWithPrefix(tmp, prefix), "and be removed when the download fails");
    }

    private static int countDirectoriesWithPrefix(Path dir, String prefix) throws IOException {
        try (var children = Files.list(dir)) {
            return (int) children.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    @Test
    void tempDirectoryIsDeletedWhenTheWorkerIsInterrupted() {
        runner.failTranscodeInterrupted = true;
        assertThrows(InterruptedException.class, () -> worker.process(media("k")));
        assertFalse(Files.exists(jobDirs.getFirst()));
    }

    @Test
    void eachJobGetsItsOwnDirectory() throws Exception {
        worker.process(media("a"));
        worker.process(media("b"));
        assertEquals(2, jobDirs.size());
        assertTrue(!jobDirs.get(0).equals(jobDirs.get(1)));
    }

    @Test
    void sweepRemovesOnlyOldDirectoriesWithTheWorkersOwnPrefix(@TempDir Path root) throws IOException {
        String prefix = VideoProcessingWorker.TEMP_DIR_PREFIX;
        Path old = Files.createDirectory(root.resolve(prefix + "old-1"));
        Files.writeString(old.resolve("input"), "x");
        Path fresh = Files.createDirectory(root.resolve(prefix + "fresh-1"));
        Path genericMedia = Files.createDirectory(root.resolve("media-old-1"));
        Path foreign = Files.createDirectory(root.resolve("something-else"));
        for (Path p : List.of(old, genericMedia, foreign)) {
            Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(Duration.ofHours(24))));
        }

        int removed = VideoProcessingWorker.sweepStaleTempDirectories(root, Duration.ofHours(6));

        assertEquals(1, removed);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh), "a recent directory may belong to a job that is running now");
        assertTrue(Files.exists(genericMedia), "a generic media-* directory is not ours and must never be touched");
        assertTrue(Files.exists(foreign));
    }

    @Test
    void sweepSkipsSymlinksAndNeverDeletesThroughThem(@TempDir Path root, @TempDir Path elsewhere) throws IOException {
        Path precious = Files.createDirectory(elsewhere.resolve("precious"));
        Path keepMe = Files.writeString(precious.resolve("keep.txt"), "important");
        Path link = Files.createSymbolicLink(root.resolve(VideoProcessingWorker.TEMP_DIR_PREFIX + "link"), precious);
        Files.setLastModifiedTime(precious, FileTime.from(Instant.now().minus(Duration.ofHours(24))));
        Files.setLastModifiedTime(keepMe, FileTime.from(Instant.now().minus(Duration.ofHours(24))));
        Files.setAttribute(link, "lastModifiedTime", FileTime.from(Instant.now().minus(Duration.ofHours(24))),
                java.nio.file.LinkOption.NOFOLLOW_LINKS);

        int removed = VideoProcessingWorker.sweepStaleTempDirectories(root, Duration.ofHours(6));

        assertEquals(0, removed);
        assertTrue(Files.isSymbolicLink(link), "the link itself is left alone");
        assertTrue(Files.exists(keepMe), "nothing behind the link may be deleted");
    }

    // ---- 3 & 4: one transcode at a time, the rest stay queued ----

    @Test
    void onlyOneTranscodeRunsAndTheNextVideoIsNotClaimedUntilItIsDone() throws Exception {
        queue.add(media("first"));
        queue.add(media("second"));
        runner.blockFirstTranscode = new CountDownLatch(1);

        worker.onUploaded(new MediaUploadedEvent("video"));
        waitUntil(() -> runner.transcodeStarted.get() == 1);
        // a second upload event arrives while the first video is still encoding
        worker.onUploaded(new MediaUploadedEvent("video"));
        Thread.sleep(300);

        assertEquals(1, runner.transcodeStarted.get(), "second video must not start encoding");
        assertEquals(1, queue.size(), "second video stays queued (still 'uploaded' in the database), not claimed");
        verify(mediaService, times(1)).claimNextUploaded(eq("video"), anyInt());

        runner.release();
        waitUntil(() -> queue.isEmpty() && runner.transcodeFinished.get() == 2);

        verify(mediaService, times(2)).markReady(any(), any(), any(), any(), any(), any());
        assertEquals(1, runner.maxConcurrentTranscodes.get());
    }

    @Test
    void manyUploadsFromManyThreadsAreAllProcessedOneAtATime() throws Exception {
        int videos = 20;
        for (int i = 0; i < videos; i++) {
            queue.add(media("v" + i));
        }
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 5; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 4; i++) {
                    worker.onUploaded(new MediaUploadedEvent("video"));
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        waitUntil(() -> runner.transcodeFinished.get() == videos);

        verify(mediaService, times(videos)).markReady(any(), any(), any(), any(), any(), any());
        assertEquals(1, runner.maxConcurrentTranscodes.get());
    }

    @Test
    void nonVideoUploadEventsDoNotWakeTheVideoWorker() throws Exception {
        queue.add(media("img"));
        worker.onUploaded(new MediaUploadedEvent("image"));
        Thread.sleep(200);
        verify(mediaService, never()).claimNextUploaded(anyString(), anyInt());
    }

    // ---- 9: failure / retry behaviour is preserved ----

    @Test
    void aFailedVideoIsMarkedForRetryAndIsNotRetriedInATightLoop() throws Exception {
        ClaimedMedia failing = media("failing");
        ClaimedMedia other = media("other");
        queue.add(failing);
        queue.add(other);
        runner.failTranscodeOnce = new IOException("ffmpeg exited with status 137");
        // like the real markFailedOrRetry, the row goes back to 'uploaded' (head of the queue: oldest first)
        doAnswer(inv -> {
            queue.addFirst(failing);
            return null;
        }).when(mediaService).markFailedOrRetry(eq(failing.id()), anyString());

        worker.onUploaded(new MediaUploadedEvent("video"));
        waitUntil(() -> mockCalled(() -> verify(mediaService).markFailedOrRetry(eq(failing.id()), anyString())));
        Thread.sleep(400);

        verify(mediaService, times(1)).claimNextUploaded(eq("video"), anyInt());
        verify(mediaService, never()).markReady(eq(other.id()), any(), any(), any(), any(), any());

        // the next recovery pass (30s later in production) retries the failed row, then continues with the queue
        worker.recoverUnprocessed();
        waitUntil(() -> runner.transcodeFinished.get() == 2);
        verify(mediaService).markReady(eq(failing.id()), any(), any(), any(), any(), any());
        verify(mediaService).markReady(eq(other.id()), any(), any(), any(), any(), any());
        verify(mediaService, times(1)).markFailedOrRetry(eq(failing.id()), anyString());
    }

    @Test
    void failureMessageKeepsTheOriginalFormat() {
        assertEquals("ffmpeg", ProcessCommandRunner.programName(List.of("nice", "-n", "10", "ffmpeg", "-y")));
        assertEquals("ffprobe", ProcessCommandRunner.programName(List.of("ffprobe", "-v")));
    }

    // ---- job-level deadline ----

    @Test
    void everyStepGetsOnlyTheTimeThatIsLeftOfOneJobBudget() throws Exception {
        runner.advanceClockPerCommand = () -> nanoClock.addAndGet(Duration.ofMinutes(1).toNanos());

        worker.process(media("k"));

        // transcode, thumbnail, 2 probes, each taking 1 simulated minute, then two uploads
        List<Duration> given = new ArrayList<>(runner.timeouts);
        assertEquals(4, given.size());
        assertEquals(Duration.ofMinutes(8), worker.jobBudget());
        assertTrue(given.getFirst().compareTo(Duration.ofMinutes(8)) <= 0, "no step may exceed the whole budget");
        for (int i = 1; i < given.size(); i++) {
            assertEquals(Duration.ofMinutes(1), given.get(i - 1).minus(given.get(i)),
                    "each step is handed what remains after the previous one");
        }
        ArgumentCaptor<Duration> upload = ArgumentCaptor.forClass(Duration.class);
        verify(storage, times(2)).putFile(anyString(), any(Path.class), anyString(), upload.capture());
        assertTrue(upload.getAllValues().stream().allMatch(d -> d.compareTo(given.getLast()) < 0),
                "the uploads get less than the last probe did");
    }

    @Test
    void aJobThatUsesUpItsBudgetStopsBeforeTheNextStepAndCleansUp() {
        // the encode "takes" 9 minutes of an 8-minute budget
        runner.advanceClockPerCommand = () -> nanoClock.addAndGet(Duration.ofMinutes(9).toNanos());

        IOException e = assertThrows(IOException.class, () -> worker.process(media("k")));

        assertTrue(e.getMessage().contains("exceeded its 8-minute time limit"), e.getMessage());
        assertEquals(1, runner.commands.size(), "no thumbnail, probe or upload may start after the deadline");
        verify(storage, never()).putFile(anyString(), any(Path.class), anyString(), any(Duration.class));
        verify(mediaService, never()).markReady(any(), any(), any(), any(), any(), any());
        assertFalse(Files.exists(jobDirs.getFirst()));
    }

    @Test
    void aSlowDownloadCountsAgainstTheSameBudget() throws Exception {
        doAnswer(inv -> {
            jobDirs.add(inv.<Path>getArgument(1).getParent());
            nanoClock.addAndGet(Duration.ofMinutes(9).toNanos());
            Files.writeString(inv.<Path>getArgument(1), "late");
            return null;
        }).when(storage).downloadToFile(anyString(), any(Path.class), any(Duration.class));

        assertThrows(IOException.class, () -> worker.process(media("k")));

        assertTrue(runner.commands.isEmpty(), "ffmpeg must not start once the download has used the budget");
        assertFalse(Files.exists(jobDirs.getFirst()));
    }

    @Test
    void theDownloadIsGivenTheFullBudget() throws Exception {
        worker.process(media("k"));
        verify(storage).downloadToFile(anyString(), any(Path.class), argThat(d -> d.equals(Duration.ofMinutes(8))));
    }

    @Test
    void theJobLimitCanNeverExceedWhatTheReaperAllows() {
        long reaperMinutes = MediaReaperJob.STALE_AFTER_MINUTES;
        for (long configured : new long[]{8, 9, 10, 60, 10_000}) {
            Duration budget = new VideoProcessingWorker(mediaService, storage, runner, 2, 10, configured, nanoClock::get)
                    .jobBudget();
            assertTrue(budget.toMinutes() <= reaperMinutes - 2,
                    configured + " minutes configured must be capped below the " + reaperMinutes + "-minute reaper");
        }
        assertEquals(Duration.ofMinutes(8), new VideoProcessingWorker(mediaService, storage, runner, 2, 10, 60, nanoClock::get).jobBudget());
        assertEquals(Duration.ofMinutes(5), new VideoProcessingWorker(mediaService, storage, runner, 2, 10, 5, nanoClock::get).jobBudget());
        assertEquals(Duration.ofMinutes(1), new VideoProcessingWorker(mediaService, storage, runner, 2, 10, 0, nanoClock::get).jobBudget());
    }

    // ---- helpers ----

    private static boolean mockCalled(Runnable verification) {
        try {
            verification.run();
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10s");
            }
            Thread.sleep(20);
        }
    }

    // Stands in for ffmpeg/ffprobe: records every command, writes the output files a real run would, can block the first
    // transcode, and can fail on demand. Counts how many transcodes overlap.
    private static final class FakeCommandRunner implements CommandRunner {
        final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        final List<Duration> timeouts = Collections.synchronizedList(new ArrayList<>());
        volatile Runnable advanceClockPerCommand;
        final AtomicInteger transcodeStarted = new AtomicInteger();
        final AtomicInteger transcodeFinished = new AtomicInteger();
        final AtomicInteger maxConcurrentTranscodes = new AtomicInteger();
        private final AtomicInteger running = new AtomicInteger();
        volatile CountDownLatch blockFirstTranscode;
        private volatile CountDownLatch gate;
        volatile IOException failTranscode;
        volatile IOException failTranscodeOnce;
        volatile boolean failTranscodeInterrupted;

        void release() {
            CountDownLatch latch = gate != null ? gate : blockFirstTranscode;
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public String run(List<String> command, Duration timeout) throws IOException, InterruptedException {
            commands.add(List.copyOf(command));
            timeouts.add(timeout);
            Runnable advance = advanceClockPerCommand;
            if (advance != null) {
                advance.run();
            }
            String program = ProcessCommandRunner.programName(command);
            if ("ffprobe".equals(program)) {
                return command.contains("stream=width,height") ? "1280x720\n" : "12.5\n";
            }
            Path output = Path.of(command.getLast());
            boolean transcode = command.contains("libx264");
            if (transcode) {
                transcodeStarted.incrementAndGet();
                int now = running.incrementAndGet();
                maxConcurrentTranscodes.accumulateAndGet(now, Math::max);
                try {
                    if (failTranscodeInterrupted) {
                        throw new InterruptedException("worker interrupted");
                    }
                    IOException once = failTranscodeOnce;
                    if (once != null) {
                        failTranscodeOnce = null;
                        throw once;
                    }
                    if (failTranscode != null) {
                        throw failTranscode;
                    }
                    CountDownLatch latch = blockFirstTranscode;
                    if (latch != null) {
                        blockFirstTranscode = null;
                        gate = latch;
                        latch.await();
                    }
                } finally {
                    running.decrementAndGet();
                }
                transcodeFinished.incrementAndGet();
            }
            Files.writeString(output, "encoded");
            return "";
        }
    }
}
