package com.redditclone.media;

import com.redditclone.common.correlation.CorrelationIdFilter;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Reclaims media rows left stuck in processing_status='processing' after a crash/restart mid-transcode —
// see MediaService.reapStaleProcessing for why this is needed. Mirrors this codebase's existing
// ReconciliationJob/RankDecayJob pattern (a periodic, ShedLock-coordinated integrity sweep).
@Component
public class MediaReaperJob {

    private static final Logger log = LoggerFactory.getLogger(MediaReaperJob.class);
    // Package-private: VideoProcessingWorker derives its job time limit from this, so the two cannot drift apart.
    static final int STALE_AFTER_MINUTES = 10;
    private static final int ABANDONED_AFTER_HOURS = 24;
    private static final int ABANDONED_BATCH_SIZE = 100;
    private static final int MAX_ABANDONED_BATCHES = 10;

    private final MediaService mediaService;

    public MediaReaperJob(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Scheduled(fixedDelay = 300000) // 5 minutes
    @SchedulerLock(name = "mediaReaperJob", lockAtLeastFor = "10s", lockAtMostFor = "1m")
    public void reap() {
        // Not tied to any request; wrapped purely so this thread's MDC state is sane for whatever job
        // runs next — see ReconciliationJob's identical comment (all @Scheduled jobs in this app share one
        // default thread).
        MDC.put(CorrelationIdFilter.MDC_KEY, "job-" + UUID.randomUUID());
        try {
            // Bounded work per run: whole batches of ABANDONED_BATCH_SIZE, at most MAX_ABANDONED_BATCHES of them. A larger
            // backlog is simply finished by the next runs (every 5 minutes), never in one long burst.
            int abandoned = 0;
            for (int i = 0; i < MAX_ABANDONED_BATCHES; i++) {
                int removed = mediaService.reapAbandonedUploads(ABANDONED_AFTER_HOURS, ABANDONED_BATCH_SIZE);
                abandoned += removed;
                if (removed < ABANDONED_BATCH_SIZE) {
                    break;
                }
            }
            if (abandoned > 0) {
                log.info("removed {} abandoned upload(s) that were never completed", abandoned);
            }
            int reaped = mediaService.reapStaleProcessing(STALE_AFTER_MINUTES);
            if (reaped > 0) {
                log.warn("reaped {} media row(s) stuck in 'processing' (crash/restart mid-job) back to 'uploaded' or 'failed'", reaped);
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
