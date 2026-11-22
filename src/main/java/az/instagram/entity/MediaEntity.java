package az.instagram.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Metadata of one uploaded file. The bytes live in object storage under {@link #objectKey}. */
@Entity
@Table(name = "media")
public class MediaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, updatable = false)
    private MediaType mediaType;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    /** What the client said it will upload; checked against the real object on complete. */
    @Column(name = "declared_size_bytes", nullable = false, updatable = false)
    private Long declaredSizeBytes;

    /** Real size read from storage. Null until the upload is verified. */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "etag")
    private String etag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Single PUT: when the presigned URL stops working.
     * Multipart: when the whole upload session ends; part URLs are issued on demand until then.
     */
    @Column(name = "upload_expires_at", nullable = false, updatable = false)
    private Instant uploadExpiresAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    /** When transcoding finished (READY) or gave up (FAILED). */
    @Column(name = "processed_at")
    private Instant processedAt;

    /** Storage-side multipart upload id. Null for a single PUT upload. Never sent to the client. */
    @Column(name = "upload_id", updatable = false)
    private String uploadId;

    /** Size of every part except the last one, which holds the remainder. */
    @Column(name = "part_size_bytes", updatable = false)
    private Long partSizeBytes;

    @Column(name = "part_count", updatable = false)
    private Integer partCount;

    /** Video: the version being produced (or last produced). Each reprocessing run gets a new one (section 8). */
    @Column(name = "target_version", nullable = false)
    private Integer targetVersion = 1;

    /** Video: the version clients play (videos/{id}/v{n}/master.m3u8). Null until the first version is READY. */
    @Column(name = "active_version")
    private Integer activeVersion;

    protected MediaEntity() {
    }

    /** Single PUT upload. */
    public MediaEntity(long userId, MediaFormat format, long declaredSizeBytes, String objectKey,
                       Instant createdAt, Instant uploadExpiresAt) {
        this.userId = userId;
        this.mediaType = format.mediaType();
        this.contentType = format.contentType();
        this.declaredSizeBytes = declaredSizeBytes;
        this.objectKey = objectKey;
        this.status = MediaStatus.PENDING_UPLOAD;
        this.createdAt = createdAt;
        this.uploadExpiresAt = uploadExpiresAt;
    }

    /** Multipart upload. */
    public static MediaEntity multipart(long userId, MediaFormat format, long declaredSizeBytes, String objectKey,
                                        Instant createdAt, Instant sessionExpiresAt,
                                        String uploadId, long partSizeBytes, int partCount) {
        MediaEntity media = new MediaEntity(userId, format, declaredSizeBytes, objectKey, createdAt, sessionExpiresAt);
        media.uploadId = uploadId;
        media.partSizeBytes = partSizeBytes;
        media.partCount = partCount;
        return media;
    }

    public boolean isMultipart() {
        return uploadId != null;
    }

    /** Exact size part {@code partNumber} (1-based) must have. */
    public long expectedPartSize(int partNumber) {
        if (partNumber < partCount) {
            return partSizeBytes;
        }
        return declaredSizeBytes - partSizeBytes * (partCount - 1);
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Integer getTargetVersion() {
        return targetVersion;
    }

    public Integer getActiveVersion() {
        return activeVersion;
    }

    public String getUploadId() {
        return uploadId;
    }

    public Long getPartSizeBytes() {
        return partSizeBytes;
    }

    public Integer getPartCount() {
        return partCount;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getDeclaredSizeBytes() {
        return declaredSizeBytes;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getEtag() {
        return etag;
    }

    public MediaStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUploadExpiresAt() {
        return uploadExpiresAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
