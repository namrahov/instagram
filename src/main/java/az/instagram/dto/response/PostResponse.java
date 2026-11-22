package az.instagram.dto.response;

import az.instagram.service.PostService.PostView;

import java.time.Instant;

/**
 * {@code likeCount}: eventually consistent, about a second behind (applied in batches).
 * {@code likedByMe}: exact, read from the viewer's own like row.
 */
public record PostResponse(
        long id,
        long userId,
        long mediaId,
        String caption,
        Instant createdAt,
        long likeCount,
        boolean likedByMe
) {
    public static PostResponse from(PostView view) {
        var post = view.post();
        return new PostResponse(post.getId(), post.getUserId(), post.getMediaId(), post.getCaption(),
                post.getCreatedAt(), view.likeCount(), view.likedByMe());
    }
}
