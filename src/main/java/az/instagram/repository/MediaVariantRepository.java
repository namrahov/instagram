package az.instagram.repository;

import az.instagram.entity.MediaVariantEntity;
import az.instagram.entity.VariantStatus;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reads only. All state changes go through {@link VariantLeaseRepository}. */
public interface MediaVariantRepository extends JpaRepository<MediaVariantEntity, MediaVariantEntity.Key> {

    List<MediaVariantEntity> findByMediaIdOrderByShortSide(long mediaId);

    List<MediaVariantEntity> findByMediaIdAndStatusOrderByShortSide(long mediaId, VariantStatus status);
}
