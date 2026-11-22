--liquibase formatted sql

--changeset instagram:002-media-multipart
-- Large videos are uploaded in parts (S3 multipart upload). upload_id is the storage-side id of that
-- upload session; it stays on the backend, the client only sees part numbers.
ALTER TABLE media
    ADD COLUMN upload_id VARCHAR(1024),
    ADD COLUMN part_size_bytes BIGINT,
    ADD COLUMN part_count INT;

ALTER TABLE media
    ADD CONSTRAINT chk_media_multipart CHECK (
        (upload_id IS NULL AND part_size_bytes IS NULL AND part_count IS NULL)
        OR (upload_id IS NOT NULL AND part_size_bytes > 0 AND part_count BETWEEN 1 AND 10000)
    );

-- ABORTED: the user cancelled. EXPIRED: the cleanup job gave up on an upload nobody completed.
ALTER TABLE media DROP CONSTRAINT chk_media_status;
ALTER TABLE media
    ADD CONSTRAINT chk_media_status CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'REJECTED', 'ABORTED', 'EXPIRED'));

-- The cleanup job only looks at unfinished uploads, a small part of the table.
CREATE INDEX idx_media_pending_expiry ON media (upload_expires_at) WHERE status = 'PENDING_UPLOAD';
--rollback DROP INDEX idx_media_pending_expiry;
--rollback ALTER TABLE media DROP CONSTRAINT chk_media_status;
--rollback ALTER TABLE media ADD CONSTRAINT chk_media_status CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'REJECTED'));
--rollback ALTER TABLE media DROP CONSTRAINT chk_media_multipart;
--rollback ALTER TABLE media DROP COLUMN upload_id, DROP COLUMN part_size_bytes, DROP COLUMN part_count;
