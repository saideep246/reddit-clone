package com.redditclone.media;

import com.redditclone.common.correlation.CorrelationIdFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

// Also handles GIFs (routed to media_type=video by MediaService — see its class comment). Video transcoding is
// minutes-long and CPU/memory-heavy, unlike image resizing, so it runs on ONE dedicated thread: at most one ffmpeg at a
// time in this application instance, and never on the @Scheduled thread that OutboxWorker's 2-second tick also depends on.
//
// The worker claims ONE row at a time, only when it is ready to start it. Claiming ahead (the old batch of 2 handed to a
// queue) would leave rows sitting in 'processing' while they wait, with MediaReaperJob's stale-processing clock already
// running; a long wait could get a queued row reaped, claimed again and processed twice. Other videos simply stay
// 'uploaded' until this thread comes round to them. Claiming is still the atomic FOR UPDATE SKIP LOCKED statement, so
// several application instances are safe: each runs at most one transcode and none can take the same row. Fresh uploads
// are claimed before retries of failed ones (see MediaService.claimNextUploaded), so one bad video cannot hold up the rest.
@Component
public class VideoProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingWorker.class);
    private static final int RECOVERY_MIN_AGE_SECONDS = 30;
    private static final Duration STALE_TEMP_DIR_AGE = Duration.ofHours(6);
    // Specific to this worker: the startup sweep deletes directories by this prefix, so it must not be one that other
    // software could plausibly use in the system temp directory.
    static final String TEMP_DIR_PREFIX = "reddit-clone-video-";
    // A job must be finished (and its row marked ready or failed) before MediaReaperJob would treat it as stuck. The job
    // gets the reaper's threshold minus this margin, which covers the final database update and clock differences between
    // the application and the database.
    private static final int JOB_LIMIT_MARGIN_MINUTES = 2;

    private final MediaService mediaService;
    private final StorageService storage;
    private final CommandRunner commands;
    private final int ffmpegThreads;
    private final int ffmpegNice;
    private final Duration jobBudget;
    private final LongSupplier nanoClock;

    private final ExecutorService transcodeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-transcode");
        t.setDaemon(true);
        return t;
    });
    // true while a drain pass is queued or running; rerun is set when another upload arrives meanwhile.
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicBoolean rerun = new AtomicBoolean(false);

    // Two constructors (this one, and the package-private one below that lets tests supply the clock), so Spring must be
    // told which to use.
    @Autowired
    public VideoProcessingWorker(MediaService mediaService, StorageService storage, CommandRunner commands,
                                 @Value("${app.media.ffmpeg-threads:2}") int ffmpegThreads,
                                 @Value("${app.media.ffmpeg-nice:10}") int ffmpegNice,
                                 @Value("${app.media.video-job-timeout-minutes:8}") long jobTimeoutMinutes) {
        this(mediaService, storage, commands, ffmpegThreads, ffmpegNice, jobTimeoutMinutes, System::nanoTime);
    }

    VideoProcessingWorker(MediaService mediaService, StorageService storage, CommandRunner commands,
                          int ffmpegThreads, int ffmpegNice, long jobTimeoutMinutes, LongSupplier nanoClock) {
        this.mediaService = mediaService;
        this.storage = storage;
        this.commands = commands;
        this.ffmpegThreads = Math.max(1, ffmpegThreads);
        this.ffmpegNice = Math.max(0, ffmpegNice);
        this.nanoClock = nanoClock;
        // Never longer than the reaper allows, whatever the configured value: a misconfiguration must not be able to let
        // a job outlive the point at which its row is handed to someone else.
        long ceiling = MediaReaperJob.STALE_AFTER_MINUTES - JOB_LIMIT_MARGIN_MINUTES;
        long minutes = Math.max(1, Math.min(jobTimeoutMinutes, ceiling));
        if (minutes != jobTimeoutMinutes) {
            log.warn("app.media.video-job-timeout-minutes={} adjusted to {}: a job must finish {} minutes before the "
                    + "{}-minute stale-processing reaper", jobTimeoutMinutes, minutes, JOB_LIMIT_MARGIN_MINUTES,
                    MediaReaperJob.STALE_AFTER_MINUTES);
        }
        this.jobBudget = Duration.ofMinutes(minutes);
    }

    Duration jobBudget() {
        return jobBudget;
    }

    // A crash or restart mid-job can leave the job's temp directory behind. Only real directories (never symlinks) with
    // this worker's own prefix are considered, and only old ones are removed. A job is limited to minutes (see jobBudget)
    // and runs on this process's one worker thread, which has not started yet when this runs, so a running job's
    // directory is never touched.
    @PostConstruct
    void sweepStaleTempDirectories() {
        try {
            int removed = sweepStaleTempDirectories(Path.of(System.getProperty("java.io.tmpdir")), STALE_TEMP_DIR_AGE);
            if (removed > 0) {
                log.info("removed {} leftover video temp director(ies) from an earlier run", removed);
            }
        } catch (RuntimeException e) {
            log.warn("could not sweep leftover video temp directories: {}", e.getMessage());
        }
    }

    static int sweepStaleTempDirectories(Path root, Duration olderThan) {
        int removed = 0;
        Instant cutoff = Instant.now().minus(olderThan);
        try (Stream<Path> children = Files.list(root)) {
            for (Path dir : children.filter(p -> p.getFileName().toString().startsWith(TEMP_DIR_PREFIX)
                    && !Files.isSymbolicLink(p) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                try {
                    if (Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                        deleteRecursively(dir);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // unreadable or vanished while we looked: leave it
                }
            }
        } catch (IOException ignored) {
        }
        return removed;
    }

    @PreDestroy
    void shutdown() {
        // Interrupts a running job so its ffmpeg is killed and its temp files removed; the row is then retried or reaped.
        transcodeExecutor.shutdownNow();
    }

    // Normal path: start as soon as the upload commits. Runs on the transcode thread, so it never delays the request
    // that completed the upload.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUploaded(MediaUploadedEvent event) {
        if ("video".equals(event.mediaType())) {
            requestDrain(0);
        }
    }

    // Recovery path: only media that has sat in 'uploaded' long enough to have missed its event, plus videos whose
    // processing failed and are due a retry. This method only wakes the worker, so the ShedLock around it stays short.
    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "videoProcessingWorker", lockAtLeastFor = "5s", lockAtMostFor = "5m")
    public void recoverUnprocessed() {
        requestDrain(RECOVERY_MIN_AGE_SECONDS);
    }

    // At most one drain pass is queued or running. An event that arrives while one is running does not start a second
    // transcode: it sets `rerun`, and the running pass looks again before it exits.
    private void requestDrain(int minAgeSeconds) {
        if (draining.compareAndSet(false, true)) {
            submitDrain(minAgeSeconds);
        } else if (minAgeSeconds == 0) {
            rerun.set(true);
        }
    }

    private void submitDrain(int minAgeSeconds) {
        try {
            transcodeExecutor.submit(() -> drainLoop(minAgeSeconds));
        } catch (RuntimeException e) { // executor shut down
            draining.set(false);
        }
    }

    private void drainLoop(int minAgeSeconds) {
        int minAge = minAgeSeconds;
        try {
            do {
                rerun.set(false);
                if (!drain(minAge)) {
                    // A job failed. Don't loop back to it: the retry waits for the next recovery pass.
                    rerun.set(false);
                    return;
                }
                minAge = 0;
            } while (rerun.get() && !Thread.currentThread().isInterrupted());
        } catch (RuntimeException e) {
            log.warn("video worker pass failed: {}", e.getMessage());
        } finally {
            draining.set(false);
            // An upload that arrived after the last check but before the flag was cleared would otherwise wait for the
            // next recovery pass.
            if (rerun.get() && draining.compareAndSet(false, true)) {
                submitDrain(0);
            }
        }
    }

    // Claim one, transcode it, repeat until nothing is waiting. A failed job ends the pass: the row went back to
    // 'uploaded' for a retry, and retrying it immediately in a loop would hammer the same failure (and the same memory
    // spike). It is retried on the next recovery pass, 30 seconds later, as before.
    // Returns false if a job failed, true if the queue was simply drained.
    private boolean drain(int minAgeSeconds) {
        while (!Thread.currentThread().isInterrupted()) {
            List<ClaimedMedia> claimed = mediaService.claimNextUploaded("video", minAgeSeconds);
            if (claimed.isEmpty()) {
                return true;
            }
            if (!runOne(claimed.getFirst())) {
                return false;
            }
        }
        return true;
    }

    private boolean runOne(ClaimedMedia claimed) {
        // MDC is ThreadLocal and the claim came from a different thread than the one that handled the request, so the
        // correlation id is re-applied here, on the thread that actually does the work.
        if (claimed.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, claimed.correlationId());
        }
        try {
            process(claimed);
            log.info("video media {} processed", claimed.id());
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("video processing failed for media {}: {}", claimed.id(), e.getMessage());
            try {
                mediaService.markFailedOrRetry(claimed.id(), e.getMessage());
            } catch (RuntimeException markFailure) {
                log.warn("could not record the failure of media {}: {}", claimed.id(), markFailure.getMessage());
            }
            return false;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    // R2 -> temp file -> ffmpeg -> temp file -> R2. No video byte ever sits in the Java heap.
    //
    // The whole job runs against one time budget (JobDeadline): every step is handed the time that is left and fails the job
    // when there is none, so download + encode + probes + uploads together cannot outlast the reaper's stale-processing
    // threshold, and a stalled R2 connection or a hung ffmpeg cannot hold the single worker thread indefinitely.
    void process(ClaimedMedia claimed) throws IOException, InterruptedException {
        JobDeadline deadline = new JobDeadline(jobBudget, nanoClock);
        // Created before the download so a failed or interrupted download is cleaned up like any other failure. The
        // directory is unique to this job, so jobs can never touch each other's files.
        Path tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX + claimed.id() + "-");
        try {
            Path inputFile = tempDir.resolve("input");
            Path outputFile = tempDir.resolve("output.mp4");
            Path thumbnailFile = tempDir.resolve("thumbnail.jpg");
            storage.downloadToFile(claimed.r2Key(), inputFile, deadline.remaining());

            // Single normalized H.264 MP4 rendition, no adaptive-bitrate ladder — capped resolution/
            // bitrate keeps one huge upload from producing an equally huge output.
            commands.run(VideoCommands.transcode(inputFile, outputFile, ffmpegThreads, ffmpegNice), deadline.remaining());
            commands.run(VideoCommands.thumbnail(inputFile, thumbnailFile, ffmpegNice), deadline.remaining());

            int[] dimensions = probeDimensions(outputFile, deadline);
            BigDecimal duration = probeDuration(outputFile, deadline);

            String displayKey = claimed.r2Key() + "-display.mp4";
            String thumbnailKey = claimed.r2Key() + "-thumb.jpg";
            storage.putFile(displayKey, outputFile, "video/mp4", deadline.remaining());
            storage.putFile(thumbnailKey, thumbnailFile, "image/jpeg", deadline.remaining());

            // Deliberately not deadline-checked: the uploads are done, and the margin below the reaper threshold exists
            // for exactly this one short update.
            mediaService.markReady(claimed.id(), thumbnailKey, displayKey,
                    dimensions[0] == 0 ? null : dimensions[0], dimensions[1] == 0 ? null : dimensions[1], duration);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private int[] probeDimensions(Path file, JobDeadline deadline) throws IOException, InterruptedException {
        String out = commands.run(VideoCommands.probeDimensions(file), deadline.remaining()).trim();
        String[] parts = out.split("x");
        if (parts.length != 2) {
            return new int[]{0, 0};
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    private BigDecimal probeDuration(Path file, JobDeadline deadline) throws IOException, InterruptedException {
        String out = commands.run(VideoCommands.probeDuration(file), deadline.remaining()).trim();
        try {
            return out.isBlank() ? null : new BigDecimal(out);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp directory; a leftover file here isn't worth failing the job over
                }
            });
        } catch (IOException ignored) {
        }
    }
}
