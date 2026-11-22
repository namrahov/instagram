--liquibase formatted sql

--changeset instagram:004-variant-leases
-- Section 7: media_variants becomes the per-rendition work table.
--   PENDING    → nobody works on it
--   PROCESSING → locked_by holds a lease until lease_until; a heartbeat keeps extending it
--   COMPLETED  → object_key points at the finished file
-- generation is the fencing token: +1 on every claim. Only the holder of the current generation may complete,
-- so a stale worker that comes back after its lease was taken over cannot overwrite the result.
ALTER TABLE media_variants
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN short_side INT,
    ADD COLUMN locked_by VARCHAR(200),
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN completed_at TIMESTAMPTZ;

-- Rows from before this change are finished renditions.
UPDATE media_variants SET short_side = LEAST(width, height), completed_at = created_at;

ALTER TABLE media_variants ALTER COLUMN status DROP DEFAULT;
ALTER TABLE media_variants ALTER COLUMN short_side SET NOT NULL;
-- Known only once the rendition is done.
ALTER TABLE media_variants ALTER COLUMN object_key DROP NOT NULL;
ALTER TABLE media_variants ALTER COLUMN width DROP NOT NULL;
ALTER TABLE media_variants ALTER COLUMN height DROP NOT NULL;
ALTER TABLE media_variants ALTER COLUMN size_bytes DROP NOT NULL;

ALTER TABLE media_variants
    ADD CONSTRAINT chk_media_variants_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED')),
    ADD CONSTRAINT chk_media_variants_short_side CHECK (short_side > 0),
    ADD CONSTRAINT chk_media_variants_generation CHECK (generation >= 0),
    ADD CONSTRAINT chk_media_variants_processing CHECK (
        status <> 'PROCESSING' OR (locked_by IS NOT NULL AND lease_until IS NOT NULL)),
    ADD CONSTRAINT chk_media_variants_completed CHECK (
        status <> 'COMPLETED'
        OR (object_key IS NOT NULL AND width IS NOT NULL AND height IS NOT NULL AND size_bytes IS NOT NULL));

-- The sweeper looks for leases that ran out; only PROCESSING rows can have one.
CREATE INDEX idx_media_variants_lease ON media_variants (lease_until) WHERE status = 'PROCESSING';
--rollback DROP INDEX idx_media_variants_lease;
--rollback DELETE FROM media_variants WHERE status <> 'COMPLETED';
--rollback ALTER TABLE media_variants DROP CONSTRAINT chk_media_variants_status, DROP CONSTRAINT chk_media_variants_short_side, DROP CONSTRAINT chk_media_variants_generation, DROP CONSTRAINT chk_media_variants_processing, DROP CONSTRAINT chk_media_variants_completed;
--rollback ALTER TABLE media_variants ALTER COLUMN object_key SET NOT NULL, ALTER COLUMN width SET NOT NULL, ALTER COLUMN height SET NOT NULL, ALTER COLUMN size_bytes SET NOT NULL;
--rollback ALTER TABLE media_variants DROP COLUMN status, DROP COLUMN short_side, DROP COLUMN locked_by, DROP COLUMN lease_until, DROP COLUMN generation, DROP COLUMN completed_at;
