package az.instagram.service;

import az.instagram.config.CdnProperties;
import az.instagram.config.MediaProperties;
import az.instagram.dto.event.VideoProcessingRequestedEvent;
import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaFormat;
import az.instagram.entity.MediaStatus;
import az.instagram.entity.MediaType;
import az.instagram.entity.MediaVariantEntity;
import az.instagram.entity.VariantStatus;
import az.instagram.repository.MediaRepository;
import az.instagram.repository.MediaVariantRepository;
import az.instagram.storage.ObjectStorage;
import az.instagram.storage.ObjectStorage.PresignedUpload;
import az.instagram.storage.ObjectStorage.StoredObject;
import az.instagram.storage.ObjectStorage.UploadedPart;
import az.instagram.transcoding.HlsPlaylists;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Direct-to-storage upload: the backend never touches media bytes.
 * <p>
 * Section 2, single PUT (small files):
 * {@link #initiate} returns one presigned PUT URL → the client uploads → {@link #complete} verifies.
 * <p>
 * Section 3, multipart (large videos): {@link #initiate} opens a multipart upload and returns the part layout →
 * the client asks for part URLs with {@link #partUrls} and uploads parts in parallel, retrying only failed ones →
 * after a crash or network loss it calls {@link #progress} to see which parts storage already has and uploads only
 * the rest → {@link #complete} joins the parts and verifies the result. {@link #abort} cancels.
 */
@Service
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    /** Caps one part-URL request, so a client cannot make the backend sign 10,000 URLs at once. */
    public static final int MAX_PART_URLS_PER_REQUEST = 100;
    /** S3 limit. */
    private static final int MAX_PARTS = 10_000;

    private final MediaRepository media;
    private final MediaVariantRepository variants;
    private final ObjectStorage storage;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final MediaProperties props;
    private final CdnProperties cdn;

    public MediaUploadService(MediaRepository media, MediaVariantRepository variants, ObjectStorage storage,
                              OutboxService outbox, TransactionTemplate tx, MediaProperties props,
                              CdnProperties cdn) {
        this.media = media;
        this.variants = variants;
        this.storage = storage;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
        this.cdn = cdn;
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
        Instant now = Instant.now();

        if (sizeBytes <= props.multipartThreshold().toBytes()) {
            PresignedUpload upload = storage.presignPut(key, format.contentType(), sizeBytes, props.uploadUrlTtl());
            MediaEntity saved = media.save(new MediaEntity(userId, format, sizeBytes, key, now, upload.expiresAt()));
            return new InitiatedUpload(saved, upload);
        }

        // Keep within S3's 10,000-part limit even if the configured part size is small for this file.
        long partSize = Math.max(props.multipartPartSize().toBytes(), Math.ceilDiv(sizeBytes, MAX_PARTS));
        int partCount = Math.toIntExact(Math.ceilDiv(sizeBytes, partSize));
        // If the row insert below fails, this session is left behind in storage; the bucket's stale-multipart
        // expiry (MinIO default 24h, S3 lifecycle rule AbortIncompleteMultipartUpload) removes it.
        String uploadId = storage.createMultipartUpload(key, format.contentType());
        MediaEntity saved = media.save(MediaEntity.multipart(userId, format, sizeBytes, key, now,
                now.plus(props.multipartSessionTtl()), uploadId, partSize, partCount));
        return new InitiatedUpload(saved, null);
    }

    /** Presigned URLs for the given parts. Each URL is signed for that part's exact size. */
    public List<PartUrl> partUrls(long userId, long mediaId, List<Integer> partNumbers) {
        MediaEntity entity = getPendingMultipart(userId, mediaId);
        Instant now = Instant.now();
        if (now.isAfter(entity.getUploadExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Upload session expired; initiate a new upload");
        }
        if (partNumbers.size() > MAX_PART_URLS_PER_REQUEST) {
            throw badRequest("At most " + MAX_PART_URLS_PER_REQUEST + " part URLs per request");
        }
        for (int partNumber : partNumbers) {
            if (partNumber < 1 || partNumber > entity.getPartCount()) {
                throw badRequest("Part number must be between 1 and " + entity.getPartCount() + ": " + partNumber);
            }
        }

        // A URL never outlives the session.
        Duration untilSessionEnd = Duration.between(now, entity.getUploadExpiresAt());
        Duration ttl = props.uploadUrlTtl().compareTo(untilSessionEnd) < 0 ? props.uploadUrlTtl() : untilSessionEnd;
        return partNumbers.stream().distinct().sorted()
                .map(n -> new PartUrl(n, storage.presignUploadPart(entity.getObjectKey(), entity.getUploadId(), n,
                        entity.expectedPartSize(n), ttl)))
                .toList();
    }

    /** Resume support: which parts storage already has, which are still missing. Asks storage, not the client. */
    public UploadProgress progress(long userId, long mediaId) {
        MediaEntity entity = getPendingMultipart(userId, mediaId);
        List<UploadedPart> parts = storage.listParts(entity.getObjectKey(), entity.getUploadId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Upload session is no longer in storage; call complete or initiate a new upload"));
        List<UploadedPart> valid = validParts(entity, parts);
        return new UploadProgress(entity, valid, missingParts(entity, valid));
    }

    /**
     * Verifies the upload. An image becomes READY. A video becomes UPLOADED and, in the same transaction,
     * a VideoProcessingRequested event is written to the outbox (section 4): either both commit or neither.
     * <p>
     * Idempotent: calling it again after success returns the same media.
     * Safe to call twice at once, or together with abort/cleanup: conditional UPDATEs let only one of them win.
     */
    public MediaEntity complete(long userId, long mediaId) {
        MediaEntity entity = getOwned(userId, mediaId);
        switch (entity.getStatus()) {
            case UPLOADED, PROCESSING, READY, FAILED -> {
                return entity;
            }
            case REJECTED -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Upload was rejected: " + entity.getFailureReason());
            case ABORTED, EXPIRED -> throw gone(entity);
            case PENDING_UPLOAD -> {
                // checked below
            }
        }

        if (entity.isMultipart()) {
            joinParts(entity);
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

        markVerified(entity, object);
        // Re-read: if a parallel complete won the race, this still returns its verified row.
        MediaEntity result = getOwned(userId, mediaId);
        if (result.getStatus() == MediaStatus.ABORTED || result.getStatus() == MediaStatus.EXPIRED) {
            // Abort/cleanup won the race while we were joining parts: do not leave the object behind.
            deleteQuietly(result);
            throw gone(result);
        }
        return result;
    }

    /** Cancels an unfinished upload and frees its storage. Idempotent. */
    public void abort(long userId, long mediaId) {
        MediaEntity entity = getOwned(userId, mediaId);
        if (entity.getStatus() == MediaStatus.ABORTED) {
            return;
        }
        if (media.markDiscarded(mediaId, MediaStatus.PENDING_UPLOAD, MediaStatus.ABORTED) == 0) {
            MediaStatus now = getOwned(userId, mediaId).getStatus();
            if (now == MediaStatus.ABORTED) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload is already " + now);
        }
        // Status first, storage second: a failure here leaves parts that the bucket expiry rule removes,
        // never a PENDING row pointing at deleted data.
        discardStorage(entity);
    }

    /**
     * Discards uploads nobody completed: the deadline passed more than {@code grace} ago.
     * Safe on several instances at once: the conditional UPDATE gives each row to one of them.
     *
     * @return how many uploads were discarded
     */
    public int expireStaleUploads() {
        Instant before = Instant.now().minus(props.cleanup().grace());
        List<MediaEntity> stale = media.findStalePending(MediaStatus.PENDING_UPLOAD, before,
                Limit.of(props.cleanup().batchSize()));
        int expired = 0;
        for (MediaEntity entity : stale) {
            if (media.markDiscarded(entity.getId(), MediaStatus.PENDING_UPLOAD, MediaStatus.EXPIRED) == 0) {
                continue; // completed or cancelled meanwhile
            }
            expired++;
            try {
                discardStorage(entity);
            } catch (RuntimeException e) {
                log.warn("Could not free storage of expired upload mediaId={}", entity.getId(), e);
            }
        }
        return expired;
    }

    public MediaEntity getOwned(long userId, long mediaId) {
        // 404 for someone else's media too: do not reveal that the id exists.
        return media.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));
    }

    /** Short-lived read URL of the original file, only for verified uploads. */
    public Optional<String> downloadUrl(MediaEntity entity) {
        if (!entity.getStatus().isVerified()) {
            return Optional.empty();
        }
        return Optional.of(storage.presignGet(entity.getObjectKey(), props.downloadUrlTtl()));
    }

    /** Renditions of the version clients play, smallest first. Empty until a version is READY. */
    public List<MediaVariantEntity> variants(MediaEntity entity) {
        if (entity.getMediaType() != MediaType.VIDEO || entity.getActiveVersion() == null) {
            return List.of();
        }
        return variants.findByMediaIdAndVersionAndStatusOrderByShortSide(entity.getId(), entity.getActiveVersion(),
                VariantStatus.COMPLETED);
    }

    /**
     * Section 8: the HLS master playlist of the active version, through the CDN. Plain, stable URL: the files are
     * immutable, so every viewer of this video shares the same cached copies at the edge. A new version (reprocess)
     * is a new URL. Stays the old one while a new version is being produced, so playback never breaks.
     */
    public Optional<String> playbackUrl(MediaEntity entity) {
        if (entity.getMediaType() != MediaType.VIDEO || entity.getActiveVersion() == null) {
            return Optional.empty();
        }
        String base = cdn.baseUrl().toString().replaceAll("/+$", "");
        return Optional.of(base + "/" + VideoProcessingService.versionPrefix(entity.getId(), entity.getActiveVersion())
                + HlsPlaylists.MASTER_PLAYLIST);
    }

    /**
     * The status change and the outbox event in one transaction. Without it, a crash between the two would leave
     * an UPLOADED video that no worker ever hears about (or an event for an upload that did not happen).
     */
    private void markVerified(MediaEntity entity, StoredObject object) {
        boolean video = entity.getMediaType() == MediaType.VIDEO;
        tx.executeWithoutResult(status -> {
            int changed = media.markVerified(entity.getId(), object.sizeBytes(), object.etag(), Instant.now(),
                    MediaStatus.PENDING_UPLOAD, video ? MediaStatus.UPLOADED : MediaStatus.READY);
            if (changed == 1 && video) {
                var event = new VideoProcessingRequestedEvent(UUID.randomUUID(), entity.getId(), entity.getUserId(),
                        Instant.now());
                // Keyed by media id: all events of one video land in one partition, handled in order.
                outbox.append(event.eventId(), VideoProcessingRequestedEvent.TYPE, entity.getId(),
                        String.valueOf(entity.getId()), event);
            }
        });
    }

    /** Turns the uploaded parts into one object, unless an earlier complete call already did. */
    private void joinParts(MediaEntity entity) {
        if (storage.head(entity.getObjectKey()).isPresent()) {
            return;
        }
        Optional<List<UploadedPart>> parts = storage.listParts(entity.getObjectKey(), entity.getUploadId());
        if (parts.isEmpty()) {
            // The session is gone: a parallel complete joined it just now (object exists) or it was discarded.
            if (storage.head(entity.getObjectKey()).isPresent()) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.GONE, "Upload session no longer exists; initiate a new upload");
        }

        List<UploadedPart> valid = validParts(entity, parts.get());
        List<Integer> missing = missingParts(entity, valid);
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    missing.size() + " part(s) missing, first: " + missing.subList(0, Math.min(10, missing.size())));
        }
        try {
            // The part list comes from storage, not from the client.
            storage.completeMultipartUpload(entity.getObjectKey(), entity.getUploadId(), valid);
        } catch (S3Exception e) {
            // A parallel complete may have finished the same session between our ListParts and this call.
            if (e.statusCode() != 404 || storage.head(entity.getObjectKey()).isEmpty()) {
                throw e;
            }
        }
    }

    /** Parts that have exactly the size their number requires. A wrong-sized part counts as missing. */
    private static List<UploadedPart> validParts(MediaEntity entity, List<UploadedPart> parts) {
        return parts.stream()
                .filter(p -> p.partNumber() >= 1 && p.partNumber() <= entity.getPartCount())
                .filter(p -> p.sizeBytes() == entity.expectedPartSize(p.partNumber()))
                .toList();
    }

    private static List<Integer> missingParts(MediaEntity entity, List<UploadedPart> valid) {
        Map<Integer, UploadedPart> byNumber = valid.stream()
                .collect(Collectors.toMap(UploadedPart::partNumber, Function.identity()));
        List<Integer> missing = new ArrayList<>();
        for (int n = 1; n <= entity.getPartCount(); n++) {
            if (!byNumber.containsKey(n)) {
                missing.add(n);
            }
        }
        return missing;
    }

    private MediaEntity getPendingMultipart(long userId, long mediaId) {
        MediaEntity entity = getOwned(userId, mediaId);
        if (!entity.isMultipart()) {
            throw badRequest("Not a multipart upload");
        }
        return switch (entity.getStatus()) {
            case PENDING_UPLOAD -> entity;
            case ABORTED, EXPIRED -> throw gone(entity);
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload is already " + entity.getStatus());
        };
    }

    private void discardStorage(MediaEntity entity) {
        if (entity.isMultipart()) {
            storage.abortMultipartUpload(entity.getObjectKey(), entity.getUploadId());
        }
        // Also for multipart: a complete racing with us may already have joined the parts into the object.
        storage.delete(entity.getObjectKey());
    }

    private void deleteQuietly(MediaEntity entity) {
        try {
            storage.delete(entity.getObjectKey());
        } catch (RuntimeException e) {
            log.warn("Could not delete object of discarded upload mediaId={}", entity.getId(), e);
        }
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

    private static ResponseStatusException gone(MediaEntity entity) {
        return new ResponseStatusException(HttpStatus.GONE, "Upload was " + entity.getStatus().name().toLowerCase()
                + "; initiate a new upload");
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    /** {@code upload} is the single PUT URL; null for a multipart upload, whose part URLs come from partUrls. */
    public record InitiatedUpload(MediaEntity media, PresignedUpload upload) {
    }

    public record PartUrl(int partNumber, PresignedUpload upload) {
    }

    public record UploadProgress(MediaEntity media, List<UploadedPart> uploadedParts, List<Integer> missingParts) {
    }
}
