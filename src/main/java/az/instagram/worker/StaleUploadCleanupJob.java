package az.instagram.worker;

import az.instagram.service.MediaUploadService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Uploads that were started but never completed: abandoned multipart sessions keep their parts in storage
 * (invisible, but they take space), and the rows stay PENDING_UPLOAD forever. This job discards both.
 */
@Component
public class StaleUploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(StaleUploadCleanupJob.class);

    private final MediaUploadService uploads;

    public StaleUploadCleanupJob(MediaUploadService uploads) {
        this.uploads = uploads;
    }

    @Scheduled(fixedDelayString = "${instagram.media.cleanup.interval:PT10M}",
            initialDelayString = "${instagram.media.cleanup.interval:PT10M}")
    public void run() {
        int expired = uploads.expireStaleUploads();
        if (expired > 0) {
            log.info("Expired {} stale upload(s)", expired);
        }
    }
}
