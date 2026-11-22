package az.instagram.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** One transcoded rendition of a video (e.g. 720p). Exists only once its file is in storage. */
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

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(nullable = false)
    private Integer width;

    @Column(nullable = false)
    private Integer height;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected MediaVariantEntity() {
    }

    public Long getMediaId() {
        return mediaId;
    }

    public String getResolution() {
        return resolution;
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
