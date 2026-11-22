package az.instagram.worker;

import az.instagram.repository.LikeRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applies queued like/unlike changes to post_like_counts in batches (section 10/11).
 * <p>
 * 10,000 likes in a second on one post become, every interval, ten batches of 1000, each one UPDATE of that post's
 * count instead of 10,000. Safe on every app instance at once: each batch locks its own queue rows (SKIP LOCKED).
 */
@Component
@ConditionalOnProperty(name = "instagram.likes.counter.enabled", havingValue = "true", matchIfMissing = true)
public class LikeCounterJob {

    private static final Logger log = LoggerFactory.getLogger(LikeCounterJob.class);

    private final LikeRepository likes;
    private final int batchSize;

    public LikeCounterJob(LikeRepository likes, @Value("${instagram.likes.counter.batch-size:1000}") int batchSize) {
        this.likes = likes;
        this.batchSize = batchSize;
    }

    /** Drains the queue: batch after batch until a batch comes back short. */
    @Scheduled(fixedDelayString = "${instagram.likes.counter.interval:PT1S}")
    public int applyQueuedLikes() {
        int total = 0;
        int batches = 0;
        int applied;
        do {
            applied = likes.applyQueuedChanges(batchSize);
            total += applied;
            batches++;
        } while (applied == batchSize);
        if (total > 0) {
            log.info("Applied {} like changes in {} batch(es)", total, batches);
        }
        return total;
    }
}
