package com.redditclone.media;

import com.redditclone.common.correlation.CorrelationIdFilter;
import net.coobird.thumbnailator.Thumbnails;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

// Produces the two public renditions of an uploaded image: a 256px thumbnail (feeds/cards) and a 1280px display
// version (post detail). Image resizing is milliseconds, so it runs inline on the worker thread rather than in a pool
// like VideoProcessingWorker's ffmpeg calls; the slow parts are the network hops to storage.
//
// Two ways in, ONE normal path:
//   * Normal: completeUpload publishes a MediaUploadedEvent; after that transaction commits, onUploaded() runs a pass
//     immediately.
//   * Recovery: a slow scheduled poll that only claims media that has sat in 'uploaded' for RECOVERY_MIN_AGE_SECONDS —
//     i.e. the event was missed (restart/crash between commit and processing). It is NOT a second normal path.
//
// Concurrency and memory: claiming is atomic (FOR UPDATE SKIP LOCKED), so a media item is never processed twice, even
// across instances. Within this instance a lock additionally serializes passes, so at most ONE image is decoded at a
// time (a decoded photo is ~3 bytes per pixel — decoding several at once is what runs a small container out of memory),
// and files beyond MAX_IMAGE_PIXELS are rejected from their header before any decoding.
@Component
public class ImageProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingWorker.class);
    private static final int BATCH_SIZE = 10;
    private static final int THUMBNAIL_MAX_DIMENSION = 256;
    private static final int DISPLAY_MAX_DIMENSION = 1280;
    private static final int RECOVERY_MIN_AGE_SECONDS = 30;

    private final MediaService mediaService;
    private final StorageService storage;
    private final long maxImagePixels;
    // Serializes processing passes within this instance (see class comment).
    private final ReentrantLock pass = new ReentrantLock();
    // Runs the event-triggered pass off the request thread. One thread: a burst of uploads queues passes back to back.
    private final ExecutorService kick = Executors.newSingleThreadExecutor(r -> daemon(r, "image-kick"));
    // The two derived files are independent, so their uploads to storage run side by side.
    private final ExecutorService uploads = Executors.newFixedThreadPool(2, r -> daemon(r, "image-upload"));

    public ImageProcessingWorker(MediaService mediaService, StorageService storage,
                                 @Value("${app.media.max-image-pixels:40000000}") long maxImagePixels) {
        this.mediaService = mediaService;
        this.storage = storage;
        this.maxImagePixels = maxImagePixels;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // Normal path. fallbackExecution covers a completeUpload that somehow runs without a transaction.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUploaded(MediaUploadedEvent event) {
        if ("image".equals(event.mediaType())) {
            kick.submit(() -> {
                pass.lock();
                try {
                    processBatch(0);
                } finally {
                    pass.unlock();
                }
            });
        }
    }

    // Recovery path: skipped entirely if a pass is already running, and only claims media old enough to have missed its event.
    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "imageProcessingWorker", lockAtLeastFor = "5s", lockAtMostFor = "5m")
    public void recoverUnprocessed() {
        if (!pass.tryLock()) {
            return;
        }
        try {
            processBatch(RECOVERY_MIN_AGE_SECONDS);
        } finally {
            pass.unlock();
        }
    }

    private void processBatch(int minAgeSeconds) {
        List<ClaimedMedia> batch = mediaService.claimUploadedBatch("image", BATCH_SIZE, minAgeSeconds);
        for (ClaimedMedia claimed : batch) {
            // Re-applies the original upload request's correlation id (see Media.correlationId) — this worker runs on
            // shared threads, so without this its log lines would carry whatever the previous job left in MDC.
            if (claimed.correlationId() != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, claimed.correlationId());
            }
            try {
                process(claimed);
                log.info("image media {} processed", claimed.id());
            } catch (UnprocessableImageException e) {
                // Retrying cannot help (not an image, or too large to decode safely): fail now so the UI says so promptly.
                log.warn("image media {} cannot be processed: {}", claimed.id(), e.getMessage());
                mediaService.markFailedPermanently(claimed.id(), e.getMessage());
            } catch (Exception e) {
                log.warn("image processing failed for media {}: {}", claimed.id(), e.getMessage());
                mediaService.markFailedOrRetry(claimed.id(), e.getMessage());
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    private void process(ClaimedMedia claimed) throws IOException {
        long t0 = System.nanoTime();
        byte[] original = storage.get(claimed.r2Key());
        long t1 = System.nanoTime();
        BufferedImage image = decode(original);
        original = null; // the encoded bytes are no longer needed; free them before resizing
        long t2 = System.nanoTime();
        // Decode once. The display size is cut from the full image; the (much smaller) thumbnail is then cut from THAT
        // instead of from the full-size original — several times less work for a large photo.
        Resized display = resize(image, DISPLAY_MAX_DIMENSION);
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        image = null;
        Resized thumbnail = resize(display.image(), THUMBNAIL_MAX_DIMENSION);
        long t3 = System.nanoTime();
        String thumbnailKey = claimed.r2Key() + "-thumb.jpg";
        String displayKey = claimed.r2Key() + "-display.jpg";
        CompletableFuture<Void> putThumbnail = CompletableFuture.runAsync(
                () -> storage.put(thumbnailKey, thumbnail.bytes(), "image/jpeg"), uploads);
        CompletableFuture<Void> putDisplay = CompletableFuture.runAsync(
                () -> storage.put(displayKey, display.bytes(), "image/jpeg"), uploads);
        try {
            CompletableFuture.allOf(putThumbnail, putDisplay).join();
        } catch (CompletionException e) {
            throw new IOException("could not store the processed image: " + e.getCause().getMessage(), e.getCause());
        }
        long t4 = System.nanoTime();
        // width/height reported to the client are the *display* variant's actual post-resize dimensions
        // (what the client will render), not the original upload's.
        mediaService.markReady(claimed.id(), thumbnailKey, displayKey, display.width(), display.height(), null);
        log.info("image media {} timings: download {}ms, decode {}ms, resize {}ms, upload {}ms ({}x{})",
                claimed.id(), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000,
                (t4 - t3) / 1_000_000, sourceWidth, sourceHeight);
    }

    // Real format sniffed from the file itself via ImageIO, never trusted from the client's declared content_type — a
    // client can lie about it. The dimensions are read from the header first, so an image bomb (a tiny file that
    // declares a gigantic canvas) is rejected before any pixel memory is allocated.
    private BufferedImage decode(byte[] original) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new UnprocessableImageException("not a decodable image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > maxImagePixels) {
                    throw new UnprocessableImageException("image is too large to process (" + reader.getWidth(0) + "x"
                            + reader.getHeight(0) + ", limit " + maxImagePixels + " pixels)");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new UnprocessableImageException("not a decodable image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private Resized resize(BufferedImage image, int maxDimension) throws IOException {
        BufferedImage resized = Thumbnails.of(image).size(maxDimension, maxDimension).asBufferedImage();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(resized, "jpg", out);
        return new Resized(out.toByteArray(), resized.getWidth(), resized.getHeight(), resized);
    }

    private record Resized(byte[] bytes, int width, int height, BufferedImage image) {
    }

    // An error a retry cannot fix.
    private static final class UnprocessableImageException extends IOException {
        UnprocessableImageException(String message) {
            super(message);
        }
    }
}
