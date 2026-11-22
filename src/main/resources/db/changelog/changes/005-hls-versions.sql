--liquibase formatted sql

--changeset instagram:005-hls-versions
-- Section 8: every processing run of a video is a version with its own paths (videos/{id}/v{n}/...), so the files
-- of a version never change and the CDN may cache them forever.
--   target_version → the version being produced (or last produced); reprocessing bumps it
--   active_version → the version clients play; switches only when the new one is READY. NULL: nothing to play yet
--                    (also for videos processed before HLS, whose renditions are plain MP4 files)
ALTER TABLE media
    ADD COLUMN target_version INT NOT NULL DEFAULT 1,
    ADD COLUMN active_version INT;

ALTER TABLE media ADD CONSTRAINT chk_media_versions CHECK (
    target_version >= 1 AND (active_version IS NULL OR active_version BETWEEN 1 AND target_version));

-- Renditions belong to a version. Bitrates go into the HLS master playlist (BANDWIDTH, AVERAGE-BANDWIDTH).
ALTER TABLE media_variants
    ADD COLUMN version INT NOT NULL DEFAULT 1,
    ADD COLUMN peak_bitrate BIGINT,
    ADD COLUMN avg_bitrate BIGINT;

ALTER TABLE media_variants ALTER COLUMN version DROP DEFAULT;
ALTER TABLE media_variants DROP CONSTRAINT media_variants_pkey;
ALTER TABLE media_variants ADD PRIMARY KEY (media_id, version, resolution);

ALTER TABLE media_variants
    ADD CONSTRAINT chk_media_variants_version CHECK (version >= 1),
    ADD CONSTRAINT chk_media_variants_bitrate CHECK (
        (peak_bitrate IS NULL OR peak_bitrate > 0) AND (avg_bitrate IS NULL OR avg_bitrate > 0));
--rollback DELETE FROM media_variants WHERE version > 1;
--rollback ALTER TABLE media_variants DROP CONSTRAINT chk_media_variants_version, DROP CONSTRAINT chk_media_variants_bitrate;
--rollback ALTER TABLE media_variants DROP CONSTRAINT media_variants_pkey;
--rollback ALTER TABLE media_variants ADD PRIMARY KEY (media_id, resolution);
--rollback ALTER TABLE media_variants DROP COLUMN version, DROP COLUMN peak_bitrate, DROP COLUMN avg_bitrate;
--rollback ALTER TABLE media DROP CONSTRAINT chk_media_versions;
--rollback ALTER TABLE media DROP COLUMN target_version, DROP COLUMN active_version;
