package az.instagram.repository;

import az.instagram.entity.MediaVariantEntity;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MediaVariantRepository extends JpaRepository<MediaVariantEntity, MediaVariantEntity.Key> {

    List<MediaVariantEntity> findByMediaIdOrderByHeight(long mediaId);

    /** Idempotent: a redelivered job that re-creates an existing rendition does not fail on the primary key. */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO media_variants (media_id, resolution, object_key, width, height, size_bytes)
            VALUES (:mediaId, :resolution, :objectKey, :width, :height, :sizeBytes)
            ON CONFLICT (media_id, resolution) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("mediaId") long mediaId, @Param("resolution") String resolution,
                       @Param("objectKey") String objectKey, @Param("width") int width, @Param("height") int height,
                       @Param("sizeBytes") long sizeBytes);
}
