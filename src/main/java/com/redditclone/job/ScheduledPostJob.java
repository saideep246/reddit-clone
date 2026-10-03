package com.redditclone.job;

import com.redditclone.post.ScheduledPostService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Publishes scheduled posts whose time has come. A post can go live up to one poll interval late — fine for
// "post this tomorrow morning", not meant for second-level precision.
@Component
public class ScheduledPostJob {

    private final ScheduledPostService scheduled;

    public ScheduledPostJob(ScheduledPostService scheduled) {
        this.scheduled = scheduled;
    }

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "scheduledPostJob", lockAtLeastFor = "5s", lockAtMostFor = "2m")
    public void publishDue() {
        scheduled.publishDue();
    }
}
