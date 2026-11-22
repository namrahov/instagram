package az.instagram.worker;

import az.instagram.repository.OutboxQueueRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Published outbox rows are only kept for debugging; without this the table grows forever. */
@Component
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);
    private static final int RETENTION_DAYS = 7;
    private static final int BATCH_SIZE = 1000;

    private final OutboxQueueRepository outbox;

    public OutboxCleanupJob(OutboxQueueRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void deleteOldPublished() {
        int total = 0;
        int deleted;
        do {
            deleted = outbox.deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            log.info("Deleted {} published outbox events older than {} days", total, RETENTION_DAYS);
        }
    }
}
