package az.instagram.repository;

import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MediaRepository extends JpaRepository<MediaEntity, Long> {

    Optional<MediaEntity> findByIdAndUserId(long id, long userId);

    /**
     * PENDING_UPLOAD → UPLOADED. The status condition makes it safe when two complete calls race:
     * only one of them changes the row (returns 1), the other gets 0.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaEntity m
               set m.status = :uploaded, m.sizeBytes = :sizeBytes, m.etag = :etag, m.uploadedAt = :uploadedAt
             where m.id = :id and m.status = :pending
            """)
    int markUploaded(@Param("id") long id, @Param("sizeBytes") long sizeBytes, @Param("etag") String etag,
                     @Param("uploadedAt") Instant uploadedAt,
                     @Param("pending") MediaStatus pending, @Param("uploaded") MediaStatus uploaded);

    /** PENDING_UPLOAD → REJECTED, same race-safety as {@link #markUploaded}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaEntity m
               set m.status = :rejected, m.failureReason = :reason
             where m.id = :id and m.status = :pending
            """)
    int markRejected(@Param("id") long id, @Param("reason") String reason,
                     @Param("pending") MediaStatus pending, @Param("rejected") MediaStatus rejected);

    /**
     * PENDING_UPLOAD → {@code terminal} (ABORTED or EXPIRED). Returns 0 if the upload already finished or was
     * already discarded, so cancel, cleanup and complete never both "win" for the same row.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaEntity m
               set m.status = :terminal
             where m.id = :id and m.status = :pending
            """)
    int markDiscarded(@Param("id") long id, @Param("pending") MediaStatus pending,
                      @Param("terminal") MediaStatus terminal);

    /** Oldest unfinished uploads whose deadline passed before {@code before}. Uses idx_media_pending_expiry. */
    @Query("""
            select m from MediaEntity m
             where m.status = :pending and m.uploadExpiresAt < :before
             order by m.uploadExpiresAt
            """)
    List<MediaEntity> findStalePending(@Param("pending") MediaStatus pending, @Param("before") Instant before,
                                       Limit limit);
}
