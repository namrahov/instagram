package az.instagram.worker;

import az.instagram.config.KafkaConfig;
import az.instagram.dto.event.VideoProcessingRequestedEvent;
import az.instagram.service.VideoProcessingService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transcoding worker (section 4). One record at a time: a job takes minutes, so polling many records at once would
 * only delay their offsets and risk a rebalance. The offset is committed after the job finishes, never before,
 * so a crash mid-job means the record is delivered again (to this or another worker).
 */
@Component
public class VideoProcessingWorker {

    private final VideoProcessingService processing;
    private final JsonMapper json;

    public VideoProcessingWorker(VideoProcessingService processing, JsonMapper json) {
        this.processing = processing;
        this.json = json;
    }

    /**
     * concurrency = partitions: more consumers than partitions in one group would sit idle (section 5).
     * max.poll.interval.ms: Kafka assumes a consumer is dead if it does not poll for this long; one job (all
     * renditions) must fit in it, or the partition is handed to another worker mid-job.
     */
    @KafkaListener(topics = KafkaConfig.VIDEO_PROCESSING_TOPIC, groupId = KafkaConfig.VIDEO_PROCESSORS_GROUP,
            concurrency = "" + KafkaConfig.VIDEO_PROCESSING_PARTITIONS,
            properties = {"max.poll.records=1", "max.poll.interval.ms=3600000"})
    public void onVideoProcessingRequested(ConsumerRecord<String, String> record) {
        var event = json.readValue(record.value(), VideoProcessingRequestedEvent.class);
        processing.process(event.mediaId());
    }
}
