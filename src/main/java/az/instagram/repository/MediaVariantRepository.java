package az.instagram.repository;

import az.instagram.entity.MediaVariantEntity;
import az.instagram.entity.VariantStatus;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reads only. All state changes go through {@link VariantLeaseRepository}. */
public interface MediaVariantRepository extends JpaRepository<MediaVariantEntity, MediaVariantEntity.Key> {

    /** Every version. */
    List<MediaVariantEntity> findByMediaIdOrderByShortSide(long mediaId);

    List<MediaVariantEntity> findByMediaIdAndVersionOrderByShortSide(long mediaId, int version);

    List<MediaVariantEntity> findByMediaIdAndVersionAndStatusOrderByShortSide(long mediaId, int version,
                                                                              VariantStatus status);
}
