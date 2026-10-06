package com.redditclone.media;

import com.redditclone.common.correlation.CorrelationIdFilter;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Also handles GIFs (routed to media_type=video by MediaService — see its class comment). Video
// transcoding is seconds-to-minutes and CPU-heavy, unlike image resizing, so this claims a smaller batch
// on a slower cadence, and hands each claimed row to its own dedicated bounded ExecutorService rather
// than running ffmpeg directly on the @Scheduled thread — that would stall Spring's shared scheduling
// pool that OutboxWorker's 2-second tick also depends on, not just Tomcat's request threads.
@Component
public class VideoProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingWorker.class);
    private static final int BATCH_SIZE = 2;
    private static final int MAX_OUTPUT_WIDTH = 1280;
    private static final int RECOVERY_MIN_AGE_SECONDS = 30;

    private final MediaService mediaService;
    private final StorageService storage;
    private final ExecutorService kick = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-kick");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService transcodeExecutor =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "video-transcode");
                t.setDaemon(true);
                return t;
            });

    public VideoProcessingWorker(MediaService mediaService, StorageService storage) {
        this.mediaService = mediaService;
        this.storage = storage;
    }

    // Normal path: start as soon as the upload commits (see ImageProcessingWorker for the full picture). The pass claims
    // rows and hands them to the bounded transcode pool; it runs on its own thread so it never delays the request that
    // completed the upload.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUploaded(MediaUploadedEvent event) {
        if ("video".equals(event.mediaType())) {
            kick.submit(() -> processBatch(0));
        }
    }

    // Recovery path: only media that has sat in 'uploaded' long enough to have missed its event. Claiming is atomic
    // (FOR UPDATE SKIP LOCKED), so this and the event path can never process the same video twice.
    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "videoProcessingWorker", lockAtLeastFor = "5s", lockAtMostFor = "5m")
    public void recoverUnprocessed() {
        processBatch(RECOVERY_MIN_AGE_SECONDS);
    }

    private void processBatch(int minAgeSeconds) {
        List<ClaimedMedia> batch = mediaService.claimUploadedBatch("video", BATCH_SIZE, minAgeSeconds);
        for (ClaimedMedia claimed : batch) {
            transcodeExecutor.submit(() -> {
                // MDC is ThreadLocal — it does NOT cross the scheduler-thread -> video-transcode-pool-
                // thread hop this submit() performs, so the id has to be re-applied here, on the actual
                // worker thread, not on the dispatching thread above.
                if (claimed.correlationId() != null) {
                    MDC.put(CorrelationIdFilter.MDC_KEY, claimed.correlationId());
                }
                try {
                    process(claimed);
                    log.info("video media {} processed", claimed.id());
                } catch (Exception e) {
                    log.warn("video processing failed for media {}: {}", claimed.id(), e.getMessage());
                    mediaService.markFailedOrRetry(claimed.id(), e.getMessage());
                } finally {
                    MDC.remove(CorrelationIdFilter.MDC_KEY);
                }
            });
        }
    }

    private void process(ClaimedMedia claimed) throws IOException, InterruptedException {
        byte[] original = storage.get(claimed.r2Key());
        Path tempDir = Files.createTempDirectory("media-" + claimed.id());
        try {
            Path inputFile = tempDir.resolve("input");
            Files.write(inputFile, original);
            Path outputFile = tempDir.resolve("output.mp4");
            Path thumbnailFile = tempDir.resolve("thumbnail.jpg");

            // Single normalized H.264 MP4 rendition, no adaptive-bitrate ladder — capped resolution/
            // bitrate keeps one huge upload from producing an equally huge output.
            runProcess("ffmpeg", "-y", "-i", inputFile.toString(),
                    "-vf", "scale='min(" + MAX_OUTPUT_WIDTH + ",iw)':'-2'",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "26",
                    "-c:a", "aac", "-b:a", "128k", outputFile.toString());
            runProcess("ffmpeg", "-y", "-i", inputFile.toString(),
                    "-ss", "00:00:00.500", "-frames:v", "1", thumbnailFile.toString());

            int[] dimensions = probeDimensions(outputFile);
            BigDecimal duration = probeDuration(outputFile);

            String displayKey = claimed.r2Key() + "-display.mp4";
            String thumbnailKey = claimed.r2Key() + "-thumb.jpg";
            storage.put(displayKey, Files.readAllBytes(outputFile), "video/mp4");
            storage.put(thumbnailKey, Files.readAllBytes(thumbnailFile), "image/jpeg");

            mediaService.markReady(claimed.id(), thumbnailKey, displayKey,
                    dimensions[0] == 0 ? null : dimensions[0], dimensions[1] == 0 ? null : dimensions[1], duration);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private int[] probeDimensions(Path file) throws IOException, InterruptedException {
        String out = runProcessCapture("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height", "-of", "csv=s=x:p=0", file.toString()).trim();
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

    private BigDecimal probeDuration(Path file) throws IOException, InterruptedException {
        String out = runProcessCapture("ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=s=,:p=0", file.toString()).trim();
        try {
            return out.isBlank() ? null : new BigDecimal(out);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void runProcess(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes(); // drain so the process can't block on a full pipe buffer
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(command[0] + " exited with status " + exit);
        }
    }

    private String runProcessCapture(String... command) throws IOException, InterruptedException {
        // redirectErrorStream, same as runProcess() above: draining stdout to completion before ever
        // touching stderr (the previous shape here) deadlocks if the child writes enough to stderr to
        // fill the OS pipe buffer while this thread is still blocked reading stdout to EOF.
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(command[0] + " exited with status " + exit);
        }
        return output;
    }

    private void deleteRecursively(Path dir) {
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
