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
     * autoStartup: off on API pods, on on video-worker pods (k8s/video-worker.yaml), so KEDA scales only workers.
     * concurrency: consumers in this pod. Locally one pod reads all partitions (3). On Kubernetes it is 1, so
     * KEDA's "replicas ≤ partitions" rule maps one pod to one partition (section 5). More consumers than
     * partitions in one group would sit idle.
     * max.poll.interval.ms: Kafka assumes a consumer is dead if it does not poll for this long; one job (all
     * renditions) must fit in it, or the partition is handed to another worker mid-job.
     */
    @KafkaListener(topics = KafkaConfig.VIDEO_PROCESSING_TOPIC, groupId = KafkaConfig.VIDEO_PROCESSORS_GROUP,
            autoStartup = "${instagram.video-worker.enabled:true}",
            concurrency = "${instagram.video-worker.concurrency:3}",
            properties = {"max.poll.records=1", "max.poll.interval.ms=3600000"})
    public void onVideoProcessingRequested(ConsumerRecord<String, String> record) {
        var event = json.readValue(record.value(), VideoProcessingRequestedEvent.class);
        processing.process(event.mediaId());
    }
}
