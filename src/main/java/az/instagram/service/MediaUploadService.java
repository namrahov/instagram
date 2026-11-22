package az.instagram.service;

import az.instagram.config.MediaProperties;
import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaFormat;
import az.instagram.entity.MediaStatus;
import az.instagram.entity.MediaType;
import az.instagram.repository.MediaRepository;
import az.instagram.storage.ObjectStorage;
import az.instagram.storage.ObjectStorage.PresignedUpload;
import az.instagram.storage.ObjectStorage.StoredObject;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Direct-to-storage upload (section 2): the backend never touches media bytes.
 * <ol>
 *   <li>{@link #initiate}: validate the request, save PENDING_UPLOAD metadata, return a short-lived presigned PUT URL.</li>
 *   <li>The client PUTs the bytes straight to MinIO/S3.</li>
 *   <li>{@link #complete}: do not trust the client; read the object from storage and check it before UPLOADED.</li>
 * </ol>
 */
@Service
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    private final MediaRepository media;
    private final ObjectStorage storage;
    private final MediaProperties props;

    public MediaUploadService(MediaRepository media, ObjectStorage storage, MediaProperties props) {
        this.media = media;
        this.storage = storage;
        this.props = props;
    }

    public InitiatedUpload initiate(long userId, String contentType, long sizeBytes) {
        MediaFormat format = MediaFormat.ofContentType(contentType)
                .orElseThrow(() -> badRequest("Unsupported content type: " + contentType));
        long maxSize = maxSize(format.mediaType());
        if (sizeBytes > maxSize) {
            throw badRequest("File is too large: " + sizeBytes + " bytes, limit for " + format.mediaType()
                    + " is " + maxSize + " bytes");
        }

        // The key is chosen by the server, never by the client: no path tricks, no overwriting someone else's file.
        String key = "media/" + userId + "/" + UUID.randomUUID() + "." + format.extension();
        PresignedUpload upload = storage.presignPut(key, format.contentType(), sizeBytes, props.uploadUrlTtl());

        MediaEntity saved = media.save(new MediaEntity(userId, format, sizeBytes, key, Instant.now(),
                upload.expiresAt()));
        return new InitiatedUpload(saved, upload);
    }

    /**
     * Idempotent: calling it again after success returns the same UPLOADED media.
     * Safe to call twice at once: the conditional UPDATE lets only one call change the status.
     */
    public MediaEntity complete(long userId, long mediaId) {
        MediaEntity entity = getOwned(userId, mediaId);
        switch (entity.getStatus()) {
            case UPLOADED -> {
                return entity;
            }
            case REJECTED -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Upload was rejected: " + entity.getFailureReason());
            case PENDING_UPLOAD -> {
                // checked below
            }
        }

        Optional<StoredObject> stored = storage.head(entity.getObjectKey());
        if (stored.isEmpty()) {
            if (Instant.now().isAfter(entity.getUploadExpiresAt())) {
                throw new ResponseStatusException(HttpStatus.GONE, "Upload URL expired; initiate a new upload");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "File is not in storage yet");
        }

        StoredObject object = stored.get();
        String mismatch = mismatch(entity, object);
        if (mismatch != null) {
            // Remove the bytes first: if this fails, the row stays PENDING_UPLOAD and the client can retry complete.
            storage.delete(entity.getObjectKey());
            media.markRejected(mediaId, mismatch, MediaStatus.PENDING_UPLOAD, MediaStatus.REJECTED);
            log.warn("Rejected upload mediaId={} userId={}: {}", mediaId, userId, mismatch);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Upload rejected: " + mismatch);
        }

        media.markUploaded(mediaId, object.sizeBytes(), object.etag(), Instant.now(),
                MediaStatus.PENDING_UPLOAD, MediaStatus.UPLOADED);
        // Re-read: if a parallel call won the race, this still returns its UPLOADED row.
        return getOwned(userId, mediaId);
    }

    public MediaEntity getOwned(long userId, long mediaId) {
        // 404 for someone else's media too: do not reveal that the id exists.
        return media.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));
    }

    /** Short-lived read URL, only for verified uploads. */
    public Optional<String> downloadUrl(MediaEntity entity) {
        if (entity.getStatus() != MediaStatus.UPLOADED) {
            return Optional.empty();
        }
        return Optional.of(storage.presignGet(entity.getObjectKey(), props.downloadUrlTtl()));
    }

    private static String mismatch(MediaEntity entity, StoredObject object) {
        if (object.sizeBytes() != entity.getDeclaredSizeBytes()) {
            return "size is " + object.sizeBytes() + " bytes, declared " + entity.getDeclaredSizeBytes();
        }
        if (!entity.getContentType().equalsIgnoreCase(object.contentType())) {
            return "content type is " + object.contentType() + ", declared " + entity.getContentType();
        }
        return null;
    }

    private long maxSize(MediaType type) {
        return switch (type) {
            case IMAGE -> props.maxImageSize().toBytes();
            case VIDEO -> props.maxVideoSize().toBytes();
        };
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record InitiatedUpload(MediaEntity media, PresignedUpload upload) {
    }
}
