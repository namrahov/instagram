package az.instagram.repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Section 7: leases and fencing on media_variants. Every method is one conditional SQL statement, so two workers
 * can never both win the same step, without holding a transaction (or a row lock) while ffmpeg runs.
 * Work items belong to one version of a video (section 8); a reprocessing run gets a fresh set.
 */
@Repository
public class VariantLeaseRepository {

    /** What a worker holds after a successful claim. {@code generation} is its fencing token. */
    public record Lease(long mediaId, int version, String resolution, int shortSide, long generation,
                        String workerId) {
    }

    public record Target(String resolution, int shortSide) {
    }

    /**
     * A finished rendition.
     *
     * @param objectKey   its HLS playlist
     * @param sizeBytes   all its segments together
     * @param peakBitrate bits per second of the busiest segment
     * @param avgBitrate  bits per second over the whole video
     */
    public record Rendition(String objectKey, int width, int height, long sizeBytes, long peakBitrate,
                            long avgBitrate) {
    }

    private final JdbcClient jdbc;

    public VariantLeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the work items once per version. Idempotent: a second worker doing the same changes nothing. */
    public void createPending(long mediaId, int version, List<Target> targets) {
        for (Target target : targets) {
            jdbc.sql("""
                            INSERT INTO media_variants (media_id, version, resolution, short_side, status)
                            VALUES (:mediaId, :version, :resolution, :shortSide, 'PENDING')
                            ON CONFLICT (media_id, version, resolution) DO NOTHING
                            """)
                    .param("mediaId", mediaId)
                    .param("version", version)
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
    public Optional<Lease> claimNext(long mediaId, int version, String workerId, Duration lease) {
        return jdbc.sql("""
                        UPDATE media_variants v
                        SET status = 'PROCESSING',
                            locked_by = :workerId,
                            lease_until = now() + make_interval(secs => :leaseSeconds),
                            generation = v.generation + 1
                        WHERE (v.media_id, v.version, v.resolution) IN (
                            SELECT media_id, version, resolution FROM media_variants
                            WHERE media_id = :mediaId AND version = :version
                              AND (status = 'PENDING' OR (status = 'PROCESSING' AND lease_until < now()))
                            ORDER BY short_side, resolution
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED)
                        RETURNING v.media_id, v.version, v.resolution, v.short_side, v.generation
                        """)
                .param("mediaId", mediaId)
                .param("version", version)
                .param("workerId", workerId)
                .param("leaseSeconds", seconds(lease))
                .query((rs, i) -> new Lease(rs.getLong("media_id"), rs.getInt("version"), rs.getString("resolution"),
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
                        WHERE media_id = :mediaId AND version = :version AND resolution = :resolution
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
     * @return false if this worker is stale; the caller must delete the objects it uploaded
     */
    public boolean complete(Lease lease, Rendition result) {
        return jdbc.sql("""
                        UPDATE media_variants
                        SET status = 'COMPLETED', object_key = :objectKey, width = :width, height = :height,
                            size_bytes = :sizeBytes, peak_bitrate = :peakBitrate, avg_bitrate = :avgBitrate,
                            completed_at = now(), locked_by = NULL, lease_until = NULL
                        WHERE media_id = :mediaId AND version = :version AND resolution = :resolution
                          AND status = 'PROCESSING' AND generation = :generation AND locked_by = :workerId
                        """)
                .param("objectKey", result.objectKey())
                .param("width", result.width())
                .param("height", result.height())
                .param("sizeBytes", result.sizeBytes())
                .param("peakBitrate", result.peakBitrate())
                .param("avgBitrate", result.avgBitrate())
                .params(leaseParams(lease))
                .update() == 1;
    }

    /** Gives the rendition back after a failure, so a retry can claim it at once instead of waiting for the lease. */
    public void release(Lease lease) {
        jdbc.sql("""
                        UPDATE media_variants SET status = 'PENDING', locked_by = NULL, lease_until = NULL
                        WHERE media_id = :mediaId AND version = :version AND resolution = :resolution
                          AND status = 'PROCESSING' AND generation = :generation AND locked_by = :workerId
                        """)
                .params(leaseParams(lease))
                .update();
    }

    public int countUnfinished(long mediaId, int version) {
        return jdbc.sql("""
                        SELECT count(*) FROM media_variants
                        WHERE media_id = :mediaId AND version = :version AND status <> 'COMPLETED'
                        """)
                .param("mediaId", mediaId)
                .param("version", version)
                .query(Integer.class)
                .single();
    }

    /**
     * Sweeper: leases that ran out more than {@code grace} ago on the version still being produced. Their worker
     * died, and nobody else will come for them: the Kafka record was already committed by a worker that saw the
     * live lease and moved on. Resets them to PENDING (the generation stays, so the dead worker is still fenced off).
     *
     * @return ids of the videos that need a new processing request
     */
    public List<Long> releaseExpired(Duration grace, int limit) {
        return jdbc.sql("""
                        UPDATE media_variants v SET status = 'PENDING', locked_by = NULL, lease_until = NULL
                        WHERE (v.media_id, v.version, v.resolution) IN (
                            SELECT mv.media_id, mv.version, mv.resolution
                            FROM media_variants mv JOIN media m ON m.id = mv.media_id
                            WHERE mv.status = 'PROCESSING'
                              AND mv.lease_until < now() - make_interval(secs => :graceSeconds)
                              AND m.status = 'PROCESSING' AND m.target_version = mv.version
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

    private static Map<String, Object> leaseParams(Lease lease) {
        return Map.of(
                "mediaId", lease.mediaId(),
                "version", lease.version(),
                "resolution", lease.resolution(),
                "generation", lease.generation(),
                "workerId", lease.workerId());
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
