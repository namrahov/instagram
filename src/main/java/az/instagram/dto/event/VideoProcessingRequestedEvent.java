package az.instagram.dto.event;

import java.time.Instant;
import java.util.UUID;

/** A verified video waits for transcoding. Carries only ids: the worker reads the current state from the DB. */
public record VideoProcessingRequestedEvent(
        UUID eventId,
        long mediaId,
        long userId,
        Instant requestedAt
) {
    public static final String TYPE = "VideoProcessingRequested";
}
