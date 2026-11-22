package az.instagram.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * An event waiting to be published. Only inserted through JPA (inside business transactions);
 * publishing and cleanup work on the table with plain SQL (OutboxQueueRepository).
 */
@Entity
@Table(name = "outbox")
public class OutboxEventEntity implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "kafka_key", nullable = false)
    private String kafkaKey;

    /** Already-serialised JSON, sent to Kafka as is. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Transient
    private boolean isNew = true;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(UUID eventId, long aggregateId, String eventType, String kafkaKey, String payload) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.kafkaKey = kafkaKey;
        this.payload = payload;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    /** The id is assigned by us, so without this Spring Data would SELECT before every INSERT. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return eventId;
    }
}
