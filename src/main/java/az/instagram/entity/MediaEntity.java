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

    /** When the presigned upload URL stops working. */
    @Column(name = "upload_expires_at", nullable = false, updatable = false)
    private Instant uploadExpiresAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    protected MediaEntity() {
    }

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
