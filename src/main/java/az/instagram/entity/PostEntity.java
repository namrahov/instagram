package az.instagram.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A published post: one uploaded media file and a caption. Its like count lives in post_like_counts. */
@Entity
@Table(name = "posts")
public class PostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "media_id", nullable = false, updatable = false)
    private Long mediaId;

    @Column(length = 2200)
    private String caption;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PostEntity() {
    }

    public PostEntity(long userId, long mediaId, String caption, Instant createdAt) {
        this.userId = userId;
        this.mediaId = mediaId;
        this.caption = caption;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getMediaId() {
        return mediaId;
    }

    public String getCaption() {
        return caption;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
