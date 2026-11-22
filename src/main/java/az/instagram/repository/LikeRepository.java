package az.instagram.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Section 10/11: likes as rows, the count as a separately maintained aggregate.
 * Plain SQL, because every step must be a single atomic statement.
 */
@Repository
public class LikeRepository {

    private final JdbcClient jdbc;

    public LikeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this created the like; false if the user had already liked the post */
    public boolean insertLike(long userId, long postId) {
        return jdbc.sql("""
                        INSERT INTO likes (user_id, post_id) VALUES (:userId, :postId)
                        ON CONFLICT (user_id, post_id) DO NOTHING
                        """)
                .param("userId", userId)
                .param("postId", postId)
                .update() == 1;
    }

    /** @return true if this removed the like; false if there was none */
    public boolean deleteLike(long userId, long postId) {
        return jdbc.sql("DELETE FROM likes WHERE user_id = :userId AND post_id = :postId")
                .param("userId", userId)
                .param("postId", postId)
                .update() == 1;
    }

    /** Queues a count change. Must run in the same transaction as the like insert/delete it describes. */
    public void appendEvent(long postId, int delta) {
        jdbc.sql("INSERT INTO like_events (post_id, delta) VALUES (:postId, :delta)")
                .param("postId", postId)
                .param("delta", delta)
                .update();
    }

    public boolean isLiked(long userId, long postId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM likes WHERE user_id = :userId AND post_id = :postId)")
                .param("userId", userId)
                .param("postId", postId)
                .query(Boolean.class)
                .single();
    }

    /** The counted likes. Trails the real number by what is still queued in like_events (about a second). */
    public long count(long postId) {
        return jdbc.sql("SELECT GREATEST(like_count, 0) FROM post_like_counts WHERE post_id = :postId")
                .param("postId", postId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /**
     * Takes up to {@code limit} queued changes, adds them up per post and applies one UPDATE per post, deleting the
     * changes it applied. One statement, so one transaction: either the batch is counted and gone, or (crash,
     * error) nothing happened and it is still queued. Nothing is counted twice, nothing is lost.
     * <ul>
     *   <li>SKIP LOCKED: several app instances can run this at once; each takes different rows.</li>
     *   <li>ORDER BY post_id: two instances lock the count rows in the same order, so they never deadlock.</li>
     *   <li>A post whose changes cancel out (like + unlike in one batch) is not written at all.</li>
     * </ul>
     * Data-modifying CTEs always run to completion, even though the final SELECT only reads {@code sums}.
     *
     * @return how many queued changes were applied
     */
    public int applyQueuedChanges(int limit) {
        return jdbc.sql("""
                        WITH batch AS (
                            DELETE FROM like_events
                            WHERE id IN (SELECT id FROM like_events ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)
                            RETURNING post_id, delta
                        ),
                        sums AS (
                            SELECT post_id, SUM(delta) AS delta, COUNT(*) AS events FROM batch GROUP BY post_id
                        ),
                        applied AS (
                            INSERT INTO post_like_counts (post_id, like_count)
                            SELECT post_id, delta FROM sums WHERE delta <> 0 ORDER BY post_id
                            ON CONFLICT (post_id) DO UPDATE
                                SET like_count = post_like_counts.like_count + EXCLUDED.like_count,
                                    updated_at = now()
                        )
                        SELECT COALESCE(SUM(events), 0)::int FROM sums
                        """)
                .param("limit", limit)
                .query(Integer.class)
                .single();
    }
}
