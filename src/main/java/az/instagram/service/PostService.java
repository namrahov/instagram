package az.instagram.service;

import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaStatus;
import az.instagram.entity.PostEntity;
import az.instagram.repository.LikeRepository;
import az.instagram.repository.MediaRepository;
import az.instagram.repository.PostRepository;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Posts and likes (section 10/11).
 * <p>
 * A like is a row in {@code likes}, plus a +1 in the {@code like_events} queue, in one transaction. Nobody updates
 * the post's counter on the request path: with 10,000 likes per second on one viral post, that single row would be
 * locked by one request after another. The {@link az.instagram.worker.LikeCounterJob} adds the queue up and
 * applies it in batches: one UPDATE per post per batch.
 * <p>
 * Consequences: whether <em>I</em> liked a post is exact at once (my row is there); the count is eventually
 * consistent, about a second behind.
 */
@Service
public class PostService {

    private final PostRepository posts;
    private final MediaRepository media;
    private final LikeRepository likes;

    public PostService(PostRepository posts, MediaRepository media, LikeRepository likes) {
        this.posts = posts;
        this.media = media;
        this.likes = likes;
    }

    /** The media must be the user's own verified upload (a video may still be processing). */
    public PostEntity create(long userId, long mediaId, String caption) {
        MediaEntity file = media.findByIdAndUserId(mediaId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));
        if (!file.getStatus().isVerified() || file.getStatus() == MediaStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Media is " + file.getStatus());
        }
        return posts.save(new PostEntity(userId, mediaId, caption, Instant.now()));
    }

    public PostView get(long viewerId, long postId) {
        PostEntity post = getPost(postId);
        return new PostView(post, likes.count(postId), likes.isLiked(viewerId, postId));
    }

    /**
     * Idempotent: liking twice (double tap, client retry, two parallel requests) leaves one like and one +1.
     * The primary key decides; only the insert that really created the row queues a change.
     */
    @Transactional
    public void like(long userId, long postId) {
        requireExists(postId);
        if (likes.insertLike(userId, postId)) {
            likes.appendEvent(postId, +1);
        }
    }

    /** Idempotent, like {@link #like}: only the delete that really removed the row queues a -1. */
    @Transactional
    public void unlike(long userId, long postId) {
        requireExists(postId);
        if (likes.deleteLike(userId, postId)) {
            likes.appendEvent(postId, -1);
        }
    }

    private void requireExists(long postId) {
        if (!posts.existsById(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }
    }

    private PostEntity getPost(long postId) {
        return posts.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
    }

    public record PostView(PostEntity post, long likeCount, boolean likedByMe) {
    }
}
