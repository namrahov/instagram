package az.instagram.worker;

import az.instagram.service.VideoProcessingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers renditions orphaned by a dead worker (section 7).
 * <p>
 * Why it is needed: worker B gets a redelivered job, sees worker A's live lease on 1080p and finishes without it
 * (its Kafka offset is committed). If A then dies, its lease runs out but no Kafka record is left to bring anyone
 * back. This job finds such leases and re-queues the video through the outbox. Safe on many instances at once.
 */
@Component
public class ExpiredLeaseSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredLeaseSweeper.class);

    private final VideoProcessingService processing;

    public ExpiredLeaseSweeper(VideoProcessingService processing) {
        this.processing = processing;
    }

    @Scheduled(fixedDelayString = "${instagram.transcoding.sweeper-interval:PT1M}",
            initialDelayString = "${instagram.transcoding.sweeper-interval:PT1M}")
    public void sweep() {
        int requeued = processing.requeueExpiredLeases();
        if (requeued > 0) {
            log.warn("Re-queued {} video(s) whose worker lease ran out", requeued);
        }
    }
}
