--liquibase formatted sql

--changeset instagram:001-media
-- Metadata only; the file itself is in object storage under object_key.
CREATE TABLE media (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    media_type VARCHAR(10) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    declared_size_bytes BIGINT NOT NULL,
    size_bytes BIGINT,
    object_key VARCHAR(512) NOT NULL,
    etag VARCHAR(100),
    status VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    upload_expires_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ,
    CONSTRAINT uq_media_object_key UNIQUE (object_key),
    CONSTRAINT chk_media_user_id CHECK (user_id > 0),
    CONSTRAINT chk_media_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT chk_media_status CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'REJECTED')),
    CONSTRAINT chk_media_declared_size CHECK (declared_size_bytes > 0),
    CONSTRAINT chk_media_uploaded CHECK (status <> 'UPLOADED' OR (size_bytes IS NOT NULL AND uploaded_at IS NOT NULL))
);
CREATE INDEX idx_media_user_created ON media (user_id, created_at DESC);
--rollback DROP TABLE media;
