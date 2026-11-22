package az.instagram.config;

import az.instagram.dto.event.VideoProcessingRequestedEvent;
import az.instagram.exception.InvalidVideoException;
import az.instagram.service.VideoProcessingService;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** Name from the design: KEDA (section 5) will scale workers on this topic's consumer lag. */
    public static final String VIDEO_PROCESSING_TOPIC = "video-processing";
    public static final String VIDEO_PROCESSORS_GROUP = "video-processors";

    /** Kafka header carrying the outbox event type. */
    public static final String EVENT_TYPE_HEADER = "event-type";

    public static final String DLT_SUFFIX = ".DLT";

    /**
     * Partitions cap how many consumers of one group read in parallel (section 5). 3 is enough locally;
     * production would use far more.
     */
    public static final int VIDEO_PROCESSING_PARTITIONS = 3;

    public static String topicFor(String eventType) {
        return switch (eventType) {
            case VideoProcessingRequestedEvent.TYPE -> VIDEO_PROCESSING_TOPIC;
            default -> throw new IllegalArgumentException("No topic for event type " + eventType);
        };
    }

    public static String dltOf(String topic) {
        return topic + DLT_SUFFIX;
    }

    /** Created on startup by KafkaAdmin if missing. */
    @Bean
    public NewTopic videoProcessingTopic() {
        return TopicBuilder.name(VIDEO_PROCESSING_TOPIC).partitions(VIDEO_PROCESSING_PARTITIONS).replicas(1).build();
    }

    /** Same partition count: a dead letter keeps its original partition. */
    @Bean
    public NewTopic videoProcessingDltTopic() {
        return TopicBuilder.name(dltOf(VIDEO_PROCESSING_TOPIC)).partitions(VIDEO_PROCESSING_PARTITIONS).replicas(1)
                .build();
    }

    /**
     * Retry policy: a failed job is retried 3 times with growing pauses (5 s, 10 s, 20 s) — enough for a storage or
     * DB blip. Then the video is marked FAILED and the record goes to {@code video-processing.DLT} to be examined
     * or replayed. A file that is not a playable video, or an unparseable payload, fails at once: retrying cannot
     * fix it.
     * <p>
     * The offset is committed only after the listener returns or the record is recovered, so a crash mid-job
     * means the record is delivered again.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafka,
                                                 VideoProcessingService videoProcessing) {
        var toDlt = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(dltOf(record.topic()), record.partition()));

        var backOff = new ExponentialBackOff(5_000, 2.0);
        backOff.setMaxAttempts(3);

        var handler = new DefaultErrorHandler((record, exception) -> {
            if (VIDEO_PROCESSING_TOPIC.equals(record.topic()) && record.key() instanceof String key) {
                try {
                    videoProcessing.markFailed(Long.parseLong(key), rootMessage(exception));
                } catch (RuntimeException e) {
                    // Still send to the DLT; the status can be fixed when the record is replayed.
                    log.error("Could not mark media {} FAILED", key, e);
                }
            }
            toDlt.accept(record, exception);
        }, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, InvalidVideoException.class);
        return handler;
    }

    private static String rootMessage(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getClass().getSimpleName() + ": " + root.getMessage();
        return message.length() > 255 ? message.substring(0, 255) : message;
    }
}
