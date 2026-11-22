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
     * PENDING_UPLOAD → {@code verified} (UPLOADED for a video, READY for an image). The status condition makes it
     * safe when two complete calls race: only one of them changes the row (returns 1), the other gets 0.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaEntity m
               set m.status = :verified, m.sizeBytes = :sizeBytes, m.etag = :etag, m.uploadedAt = :uploadedAt
             where m.id = :id and m.status = :pending
            """)
    int markVerified(@Param("id") long id, @Param("sizeBytes") long sizeBytes, @Param("etag") String etag,
                     @Param("uploadedAt") Instant uploadedAt,
                     @Param("pending") MediaStatus pending, @Param("verified") MediaStatus verified);

    /**
     * UPLOADED → PROCESSING. Also matches PROCESSING: a redelivered event after a worker crash continues the job.
     *
     * @return 0 if the video is not waiting for processing (already READY/FAILED, or unknown)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaEntity m
               set m.status = az.instagram.entity.MediaStatus.PROCESSING
             where m.id = :id
               and m.status in (az.instagram.entity.MediaStatus.UPLOADED, az.instagram.entity.MediaStatus.PROCESSING)
            """)
    int startProcessing(@Param("id") long id);

    /**
     * PROCESSING → READY, and {@code version} becomes the one clients play. Only for the version being produced:
     * a late worker of an older version cannot switch playback back to it.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaEntity m
               set m.status = az.instagram.entity.MediaStatus.READY, m.activeVersion = :version, m.processedAt = :at
             where m.id = :id and m.status = az.instagram.entity.MediaStatus.PROCESSING
               and m.targetVersion = :version
            """)
    int markReady(@Param("id") long id, @Param("version") int version, @Param("at") Instant at);

    /**
     * READY/FAILED → UPLOADED with the next version, so the worker produces a new set of files next to the old ones.
     * The active version keeps playing meanwhile. Not while a version is being produced: one job per video at a time.
     *
     * @return 0 if the video is not the user's, not a video, or still uploading/processing
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaEntity m
               set m.status = az.instagram.entity.MediaStatus.UPLOADED, m.targetVersion = m.targetVersion + 1,
                   m.failureReason = null, m.processedAt = null
             where m.id = :id and m.userId = :userId
               and m.mediaType = az.instagram.entity.MediaType.VIDEO
               and m.status in (az.instagram.entity.MediaStatus.READY, az.instagram.entity.MediaStatus.FAILED)
            """)
    int requestReprocessing(@Param("id") long id, @Param("userId") long userId);

    /** UPLOADED/PROCESSING → FAILED, after the retries ran out. A READY video is never downgraded. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaEntity m
               set m.status = az.instagram.entity.MediaStatus.FAILED, m.failureReason = :reason, m.processedAt = :at
             where m.id = :id
               and m.status in (az.instagram.entity.MediaStatus.UPLOADED, az.instagram.entity.MediaStatus.PROCESSING)
            """)
    int markFailed(@Param("id") long id, @Param("reason") String reason, @Param("at") Instant at);

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
