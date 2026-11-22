--liquibase formatted sql

--changeset instagram:003-media-processing-status
-- Video lifecycle after upload: UPLOADED → PROCESSING → READY, or FAILED after retries.
-- Images need no processing and become READY on complete.
ALTER TABLE media DROP CONSTRAINT chk_media_status;
ALTER TABLE media
    ADD CONSTRAINT chk_media_status CHECK (status IN (
        'PENDING_UPLOAD', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'REJECTED', 'ABORTED', 'EXPIRED'));
-- Every status after a verified upload must have the verified size.
ALTER TABLE media DROP CONSTRAINT chk_media_uploaded;
ALTER TABLE media
    ADD CONSTRAINT chk_media_uploaded CHECK (
        status NOT IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED')
        OR (size_bytes IS NOT NULL AND uploaded_at IS NOT NULL));
ALTER TABLE media ADD COLUMN processed_at TIMESTAMPTZ;
--rollback ALTER TABLE media DROP COLUMN processed_at;
--rollback ALTER TABLE media DROP CONSTRAINT chk_media_uploaded;
--rollback ALTER TABLE media ADD CONSTRAINT chk_media_uploaded CHECK (status <> 'UPLOADED' OR (size_bytes IS NOT NULL AND uploaded_at IS NOT NULL));
--rollback ALTER TABLE media DROP CONSTRAINT chk_media_status;
--rollback ALTER TABLE media ADD CONSTRAINT chk_media_status CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'REJECTED', 'ABORTED', 'EXPIRED'));

--changeset instagram:003-outbox
-- Transactional outbox: the event is written in the same transaction as the status change, so a committed
-- UPLOADED always has its VideoProcessingRequested event. OutboxPublisher moves rows to Kafka afterwards.
CREATE TABLE outbox (
    event_id UUID PRIMARY KEY,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    -- Kafka key: events with the same key go to the same partition and keep their order.
    kafka_key VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    -- Claim-based publishing: no row lock is held while waiting for Kafka; a crashed publisher's claim expires.
    locked_until TIMESTAMPTZ,
    CONSTRAINT chk_outbox_event_type CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_outbox_pending ON outbox (created_at, event_id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE status = 'PUBLISHED';
--rollback DROP TABLE outbox;

--changeset instagram:003-media-variants
-- One row per transcoded rendition. A variant row exists only after its file is in storage, so a worker that
-- picks the job up again (crash, redelivery) skips the renditions that are already done.
CREATE TABLE media_variants (
    media_id BIGINT NOT NULL REFERENCES media(id),
    resolution VARCHAR(10) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (media_id, resolution),
    CONSTRAINT uq_media_variants_object_key UNIQUE (object_key),
    CONSTRAINT chk_media_variants_dimensions CHECK (width > 0 AND height > 0),
    CONSTRAINT chk_media_variants_size CHECK (size_bytes > 0)
);
--rollback DROP TABLE media_variants;
