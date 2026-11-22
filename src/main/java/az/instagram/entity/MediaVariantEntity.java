package az.instagram.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One rendition of a video (e.g. 720p) and the work on it. Read through JPA; claimed, extended and completed with
 * conditional SQL in VariantLeaseRepository, because those steps must be atomic.
 */
@Entity
@Table(name = "media_variants")
@IdClass(MediaVariantEntity.Key.class)
public class MediaVariantEntity {

    @Id
    @Column(name = "media_id")
    private Long mediaId;

    @Id
    @Column(name = "resolution")
    private String resolution;

    /** Target short side in pixels: 720 for "720p" (720x1280 for portrait, 1280x720 for landscape). */
    @Column(name = "short_side", nullable = false)
    private Integer shortSide;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VariantStatus status;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    /** Fencing token: incremented on every claim. */
    @Column(nullable = false)
    private Long generation;

    /** Set when COMPLETED. */
    @Column(name = "object_key")
    private String objectKey;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected MediaVariantEntity() {
    }

    public Long getMediaId() {
        return mediaId;
    }

    public String getResolution() {
        return resolution;
    }

    public Integer getShortSide() {
        return shortSide;
    }

    public VariantStatus getStatus() {
        return status;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Long getGeneration() {
        return generation;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public record Key(Long mediaId, String resolution) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
