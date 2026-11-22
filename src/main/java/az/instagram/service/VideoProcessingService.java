package az.instagram.service;

import az.instagram.config.TranscodingProperties;
import az.instagram.dto.event.VideoProcessingRequestedEvent;
import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaType;
import az.instagram.entity.MediaVariantEntity;
import az.instagram.entity.VariantStatus;
import az.instagram.exception.LeaseLostException;
import az.instagram.repository.MediaRepository;
import az.instagram.repository.MediaVariantRepository;
import az.instagram.repository.VariantLeaseRepository;
import az.instagram.repository.VariantLeaseRepository.Lease;
import az.instagram.repository.VariantLeaseRepository.Target;
import az.instagram.storage.ObjectStorage;
import az.instagram.transcoding.FfmpegTranscoder;
import az.instagram.transcoding.FfmpegTranscoder.VideoInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Section 4 + 7: turns a verified original video into 360p/720p/1080p renditions, safely under concurrency.
 * <p>
 * Each rendition is a work item in media_variants. A worker loops: claim the next free rendition (atomic UPDATE,
 * lease + new generation), transcode it while a heartbeat extends the lease, upload it under a key unique to this
 * attempt, then complete it only if its generation is still current (fencing token). So:
 * <ul>
 *   <li>a duplicate or redelivered job skips COMPLETED renditions and renditions another worker holds;</li>
 *   <li>two workers on one video split the renditions between them instead of doing them twice;</li>
 *   <li>a worker that died loses its lease when it runs out, and the rendition is claimed again;</li>
 *   <li>a stale worker (paused, then back after its lease was taken) cannot overwrite the result: its complete
 *       is rejected and it deletes its own upload; the heartbeat notices too and stops its ffmpeg early.</li>
 * </ul>
 * The last worker to complete a rendition marks the video READY.
 */
@Service
public class VideoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingService.class);
    private static final int SWEEP_BATCH = 100;

    private final MediaRepository media;
    private final MediaVariantRepository variants;
    private final VariantLeaseRepository leases;
    private final ObjectStorage storage;
    private final FfmpegTranscoder transcoder;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final TranscodingProperties props;

    /** Shows up as locked_by: pod name on Kubernetes (HOSTNAME), host and pid locally. */
    private final String workerId = workerId();

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("lease-heartbeat").daemon().factory());

    public VideoProcessingService(MediaRepository media, MediaVariantRepository variants,
                                  VariantLeaseRepository leases, ObjectStorage storage, FfmpegTranscoder transcoder,
                                  OutboxService outbox, TransactionTemplate tx, TranscodingProperties props) {
        this.media = media;
        this.variants = variants;
        this.leases = leases;
        this.storage = storage;
        this.transcoder = transcoder;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
    }

    @PreDestroy
    void stopHeartbeats() {
        heartbeats.shutdownNow();
    }

    /** Throws on failure; the Kafka error handler retries and finally marks the video FAILED. */
    public void process(long mediaId) {
        MediaEntity video = media.findById(mediaId).orElse(null);
        if (video == null || video.getMediaType() != MediaType.VIDEO) {
            log.warn("Ignoring processing request for unknown or non-video media {}", mediaId);
            return;
        }
        if (media.startProcessing(mediaId) == 0) {
            log.info("Media {} is {}, nothing to do (duplicate event)", mediaId, video.getStatus());
            return;
        }

        Path dir = props.workDir().resolve("media-" + mediaId + "-" + UUID.randomUUID());
        try {
            Files.createDirectories(dir);
            Path original = null; // downloaded only if this worker gets something to do

            if (variants.findByMediaIdOrderByShortSide(mediaId).isEmpty()) {
                original = download(video, dir);
                leases.createPending(mediaId, targets(transcoder.probe(original)));
            }

            Optional<Lease> lease;
            while ((lease = leases.claimNext(mediaId, workerId, props.leaseDuration())).isPresent()) {
                if (original == null) {
                    original = download(video, dir);
                }
                produce(lease.get(), original, dir);
            }

            finishIfComplete(mediaId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /** Called by the Kafka error handler once retries are exhausted (or the file is not a video). */
    public void markFailed(long mediaId, String reason) {
        if (media.markFailed(mediaId, reason, Instant.now()) == 1) {
            log.warn("Media {} FAILED: {}", mediaId, reason);
        }
    }

    /**
     * Sweeper: renditions whose lease ran out (worker died) on a video nobody is working on any more.
     * Resets them and asks for processing again through the outbox, in one transaction.
     *
     * @return how many videos were re-queued
     */
    public int requeueExpiredLeases() {
        Integer requeued = tx.execute(status -> {
            List<Long> mediaIds = leases.releaseExpired(props.sweeperGrace(), SWEEP_BATCH);
            for (long mediaId : mediaIds) {
                MediaEntity video = media.findById(mediaId).orElseThrow();
                var event = new VideoProcessingRequestedEvent(UUID.randomUUID(), mediaId, video.getUserId(),
                        Instant.now());
                outbox.append(event.eventId(), VideoProcessingRequestedEvent.TYPE, mediaId,
                        String.valueOf(mediaId), event);
            }
            return mediaIds.size();
        });
        return requeued == null ? 0 : requeued;
    }

    /** Transcodes one claimed rendition under a heartbeat and publishes it if we still hold the lease. */
    private void produce(Lease lease, Path original, Path dir) {
        String resolution = lease.resolution();
        // Unique per attempt: two attempts never write the same object, so a stale one cannot clobber a live one.
        String key = "videos/%d/%s/g%d.mp4".formatted(lease.mediaId(), resolution, lease.generation());
        Path rendition = dir.resolve(resolution + "-g" + lease.generation() + ".mp4");

        AtomicBoolean lost = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = startHeartbeat(lease, lost);
        long started = System.nanoTime();
        try {
            transcoder.transcode(original, rendition, lease.shortSide(), () -> !lost.get());
            VideoInfo produced = transcoder.probe(rendition);
            storage.upload(key, rendition, "video/mp4");

            if (!leases.complete(lease, key, produced.width(), produced.height(), Files.size(rendition))) {
                // Fenced off: a newer generation owns this rendition. Ours must not stay in storage.
                log.warn("Media {}: {} generation {} is stale, discarding its result", lease.mediaId(), resolution,
                        lease.generation());
                deleteObjectQuietly(key);
                return;
            }
            log.info("Media {}: {} ({}x{}) done in {} ms, generation {}", lease.mediaId(), resolution,
                    produced.width(), produced.height(), (System.nanoTime() - started) / 1_000_000,
                    lease.generation());
        } catch (LeaseLostException e) {
            log.warn("Media {}: lost the lease on {} (generation {}), stopped", lease.mediaId(), resolution,
                    lease.generation());
        } catch (IOException e) {
            leases.release(lease);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            // Give the rendition back now, so the Kafka retry can claim it without waiting for the lease to run out.
            leases.release(lease);
            throw e;
        } finally {
            heartbeat.cancel(false);
            try {
                Files.deleteIfExists(rendition);
            } catch (IOException e) {
                log.debug("Could not delete {}", rendition, e);
            }
        }
    }

    private ScheduledFuture<?> startHeartbeat(Lease lease, AtomicBoolean lost) {
        long every = props.heartbeatInterval().toMillis();
        return heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (!leases.extend(lease, props.leaseDuration())) {
                    lost.set(true);
                }
            } catch (RuntimeException e) {
                // A DB blip: try again next beat. If it lasts, the lease runs out and fencing protects the result.
                log.warn("Heartbeat for media {} {} failed", lease.mediaId(), lease.resolution(), e);
            }
        }, every, every, TimeUnit.MILLISECONDS);
    }

    /** READY once every rendition is COMPLETED. Workers still holding live leases will get here themselves. */
    private void finishIfComplete(long mediaId) {
        int unfinished = leases.countUnfinished(mediaId);
        if (unfinished > 0) {
            log.info("Media {}: {} rendition(s) held by other workers, leaving them", mediaId, unfinished);
            return;
        }
        if (media.markReady(mediaId, Instant.now()) == 1) {
            log.info("Media {} is READY", mediaId);
            deleteOrphans(mediaId);
        }
    }

    /** Results of attempts that died between upload and complete. Stale workers that are still running delete their own. */
    private void deleteOrphans(long mediaId) {
        try {
            Set<String> active = variants.findByMediaIdAndStatusOrderByShortSide(mediaId, VariantStatus.COMPLETED)
                    .stream().map(MediaVariantEntity::getObjectKey).collect(Collectors.toSet());
            for (String key : storage.listKeys("videos/" + mediaId + "/")) {
                if (!active.contains(key)) {
                    storage.delete(key);
                    log.info("Media {}: deleted orphan {}", mediaId, key);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Media {}: orphan cleanup failed", mediaId, e);
        }
    }

    private List<Target> targets(VideoInfo source) {
        return FfmpegTranscoder.targetHeights(props.heights(), source.shortSide()).stream()
                .map(h -> new Target(h + "p", h))
                .toList();
    }

    private Path download(MediaEntity video, Path dir) {
        Path original = dir.resolve("original");
        storage.download(video.getObjectKey(), original);
        return original;
    }

    private void deleteObjectQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete stale object {}; orphan cleanup will retry", key, e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Could not delete work dir {}", dir, e);
        }
    }

    private static String workerId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        String jvm = ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"
        return (host == null ? "worker" : host) + "-" + jvm.substring(0, jvm.indexOf('@'));
    }
}
