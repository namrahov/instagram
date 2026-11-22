package az.instagram.repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Section 7: leases and fencing on media_variants. Every method is one conditional SQL statement, so two workers
 * can never both win the same step, without holding a transaction (or a row lock) while ffmpeg runs.
 */
@Repository
public class VariantLeaseRepository {

    /** What a worker holds after a successful claim. {@code generation} is its fencing token. */
    public record Lease(long mediaId, String resolution, int shortSide, long generation, String workerId) {
    }

    public record Target(String resolution, int shortSide) {
    }

    private final JdbcClient jdbc;

    public VariantLeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the work items once per video. Idempotent: a second worker doing the same changes nothing. */
    public void createPending(long mediaId, List<Target> targets) {
        for (Target target : targets) {
            jdbc.sql("""
                            INSERT INTO media_variants (media_id, resolution, short_side, status)
                            VALUES (:mediaId, :resolution, :shortSide, 'PENDING')
                            ON CONFLICT (media_id, resolution) DO NOTHING
                            """)
                    .param("mediaId", mediaId)
                    .param("resolution", target.resolution())
                    .param("shortSide", target.shortSide())
                    .update();
        }
    }

    /**
     * Takes the smallest rendition nobody is working on: PENDING, or PROCESSING whose lease ran out (its worker
     * died or hangs). Bumps the generation, which fences off the previous holder.
     * <p>
     * SKIP LOCKED: two workers claiming at the same moment take different renditions instead of waiting.
     * Postgres re-checks the WHERE on the locked row, so a claim committed by someone else meanwhile is respected.
     *
     * @return empty if every rendition is COMPLETED or held by a live lease
     */
    public Optional<Lease> claimNext(long mediaId, String workerId, Duration lease) {
        return jdbc.sql("""
                        UPDATE media_variants v
                        SET status = 'PROCESSING',
                            locked_by = :workerId,
                            lease_until = now() + make_interval(secs => :leaseSeconds),
                            generation = v.generation + 1
                        WHERE (v.media_id, v.resolution) IN (
                            SELECT media_id, resolution FROM media_variants
                            WHERE media_id = :mediaId
                              AND (status = 'PENDING' OR (status = 'PROCESSING' AND lease_until < now()))
                            ORDER BY short_side, resolution
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED)
                        RETURNING v.media_id, v.resolution, v.short_side, v.generation
                        """)
                .param("mediaId", mediaId)
                .param("workerId", workerId)
                .param("leaseSeconds", seconds(lease))
                .query((rs, i) -> new Lease(rs.getLong("media_id"), rs.getString("resolution"),
                        rs.getInt("short_side"), rs.getLong("generation"), workerId))
                .optional();
    }

    /**
     * Heartbeat: pushes the lease forward while ffmpeg runs.
     *
     * @return false if the lease is no longer ours (taken over with a newer generation, or released by the sweeper)
     */
    public boolean extend(Lease lease, Duration duration) {
        return jdbc.sql("""
                        UPDATE media_variants
                        SET lease_until = now() + make_interval(secs => :leaseSeconds)
                        WHERE media_id = :mediaId AND resolution = :resolution
                          AND status = 'PROCESSING' AND generation = :generation AND locked_by = :workerId
                        """)
                .param("leaseSeconds", seconds(duration))
                .params(leaseParams(lease))
                .update() == 1;
    }

    /**
     * Publishes the result, fenced by the generation: a stale worker (lease expired and claimed by someone else)
     * changes nothing. A lease that expired but was not taken over is still ours, so the work is not wasted.
     *
     * @return false if this worker is stale; the caller must delete the object it uploaded
     */
    public boolean complete(Lease lease, String objectKey, int width, int height, long sizeBytes) {
        return jdbc.sql("""
                        UPDATE media_variants
                        SET status = 'COMPLETED', object_key = :objectKey, width = :width, height = :height,
                            size_bytes = :sizeBytes, completed_at = now(), locked_by = NULL, lease_until = NULL
                        WHERE media_id = :mediaId AND resolution = :resolution
                          AND status = 'PROCESSING' AND generation = :generation AND locked_by = :workerId
                        """)
                .param("objectKey", objectKey)
                .param("width", width)
                .param("height", height)
                .param("sizeBytes", sizeBytes)
                .params(leaseParams(lease))
                .update() == 1;
    }

    /** Gives the rendition back after a failure, so a retry can claim it at once instead of waiting for the lease. */
    public void release(Lease lease) {
        jdbc.sql("""
                        UPDATE media_variants SET status = 'PENDING', locked_by = NULL, lease_until = NULL
                        WHERE media_id = :mediaId AND resolution = :resolution
                          AND status = 'PROCESSING' AND generation = :generation AND locked_by = :workerId
                        """)
                .params(leaseParams(lease))
                .update();
    }

    public int countUnfinished(long mediaId) {
        return jdbc.sql("SELECT count(*) FROM media_variants WHERE media_id = :mediaId AND status <> 'COMPLETED'")
                .param("mediaId", mediaId)
                .query(Integer.class)
                .single();
    }

    /**
     * Sweeper: leases that ran out more than {@code grace} ago on videos still PROCESSING. Their worker died, and
     * nobody else will come for them: the Kafka record was already committed by a worker that saw the live lease
     * and moved on. Resets them to PENDING (the generation stays, so the dead worker is still fenced off).
     *
     * @return ids of the videos that need a new processing request
     */
    public List<Long> releaseExpired(Duration grace, int limit) {
        return jdbc.sql("""
                        UPDATE media_variants v SET status = 'PENDING', locked_by = NULL, lease_until = NULL
                        WHERE (v.media_id, v.resolution) IN (
                            SELECT mv.media_id, mv.resolution
                            FROM media_variants mv JOIN media m ON m.id = mv.media_id
                            WHERE mv.status = 'PROCESSING'
                              AND mv.lease_until < now() - make_interval(secs => :graceSeconds)
                              AND m.status = 'PROCESSING'
                            ORDER BY mv.lease_until
                            LIMIT :limit
                            FOR UPDATE OF mv SKIP LOCKED)
                        RETURNING v.media_id
                        """)
                .param("graceSeconds", seconds(grace))
                .param("limit", limit)
                .query(Long.class)
                .list()
                .stream()
                .distinct()
                .toList();
    }

    private static java.util.Map<String, Object> leaseParams(Lease lease) {
        return java.util.Map.of(
                "mediaId", lease.mediaId(),
                "resolution", lease.resolution(),
                "generation", lease.generation(),
                "workerId", lease.workerId());
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
