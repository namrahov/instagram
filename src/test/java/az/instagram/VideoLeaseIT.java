package az.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import az.instagram.config.TranscodingProperties;
import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaFormat;
import az.instagram.entity.MediaStatus;
import az.instagram.entity.MediaVariantEntity;
import az.instagram.entity.VariantStatus;
import az.instagram.repository.MediaRepository;
import az.instagram.repository.MediaVariantRepository;
import az.instagram.repository.VariantLeaseRepository;
import az.instagram.repository.VariantLeaseRepository.Lease;
import az.instagram.repository.VariantLeaseRepository.Target;
import az.instagram.service.VideoProcessingService;
import az.instagram.storage.ObjectStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Section 7 against the real PostgreSQL, MinIO and ffmpeg. The Kafka worker and the outbox publisher are off:
 * the tests call the processing service directly, from several threads where needed.
 * Run with FFMPEG_PATH/FFPROBE_PATH set if ffmpeg is not on PATH: {@code ./gradlew integrationTest}.
 */
@SpringBootTest(properties = {
        "instagram.video-worker.enabled=false",
        "instagram.outbox.publisher.enabled=false",
        "instagram.transcoding.heights=360,720",
        "instagram.transcoding.lease-duration=6s",
        "instagram.transcoding.heartbeat-interval=1s",
        "instagram.transcoding.sweeper-grace=0s",
        "instagram.transcoding.sweeper-interval=PT1H",
})
class VideoLeaseIT {

    private static final long USER_ID = 999_999;
    private static final Duration LEASE = Duration.ofSeconds(6);
    /** 2 minutes of 720p: long enough that a 720p transcode takes several seconds. */
    private static Path source;

    @Autowired VideoProcessingService processing;
    @Autowired VariantLeaseRepository leases;
    @Autowired MediaRepository media;
    @Autowired MediaVariantRepository variants;
    @Autowired ObjectStorage storage;
    @Autowired JdbcClient jdbc;
    @Autowired TranscodingProperties props;

    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void makeSourceVideo() throws Exception {
        if (source != null) {
            return;
        }
        source = Files.createTempFile("lease-it-", ".mp4");
        Process ffmpeg = new ProcessBuilder(props.ffmpegPath(), "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=duration=120:size=1280x720:rate=30",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "32", "-pix_fmt", "yuv420p", source.toString())
                .inheritIO().start();
        assertThat(ffmpeg.waitFor(120, TimeUnit.SECONDS) && ffmpeg.exitValue() == 0).as("test video created").isTrue();
    }

    @AfterEach
    void cleanUp() {
        for (long id : created) {
            storage.listKeys("videos/" + id + "/").forEach(storage::delete);
            media.findById(id).ifPresent(m -> storage.delete(m.getObjectKey()));
            jdbc.sql("DELETE FROM media_variants WHERE media_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM outbox WHERE aggregate_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM media WHERE id = :id").param("id", id).update();
        }
    }

    @Test
    void twoWorkersOnOneVideoSplitTheRenditionsInsteadOfDoingThemTwice() throws Exception {
        long id = uploadedVideo();

        CountDownLatch go = new CountDownLatch(1);
        Runnable worker = () -> {
            await(go);
            processing.process(id);
        };
        CompletableFuture<Void> a = CompletableFuture.runAsync(worker);
        CompletableFuture<Void> b = CompletableFuture.runAsync(worker);
        go.countDown();
        CompletableFuture.allOf(a, b).get(120, TimeUnit.SECONDS);

        assertThat(status(id)).isEqualTo(MediaStatus.READY);
        Map<String, MediaVariantEntity> rows = rows(id);
        assertThat(rows.keySet()).containsExactlyInAnyOrder("360p", "720p");
        // Generation 1 everywhere: no rendition was claimed twice.
        assertThat(rows.values()).allSatisfy(v -> {
            assertThat(v.getStatus()).isEqualTo(VariantStatus.COMPLETED);
            assertThat(v.getGeneration()).isEqualTo(1);
        });
        assertThat(storage.listKeys("videos/" + id + "/")).containsExactlyInAnyOrder(
                "videos/" + id + "/360p/g1.mp4", "videos/" + id + "/720p/g1.mp4");
    }

    @Test
    void staleWorkerCannotPublishAfterItsLeaseWasTakenOver() {
        long id = uploadedVideo();
        leases.createPending(id, List.of(new Target("360p", 360), new Target("720p", 720)));

        // The zombie claims 360p (generation 1) and then hangs: its lease runs out.
        Lease zombie = leases.claimNext(id, "zombie", LEASE).orElseThrow();
        assertThat(zombie.resolution()).isEqualTo("360p");
        expireLeases(id);

        // A live worker takes over 360p (generation 2) and does 720p too.
        processing.process(id);
        assertThat(status(id)).isEqualTo(MediaStatus.READY);
        MediaVariantEntity p360 = rows(id).get("360p");
        assertThat(p360.getGeneration()).isEqualTo(2);
        assertThat(p360.getObjectKey()).isEqualTo("videos/" + id + "/360p/g2.mp4");

        // The zombie wakes up and tries to publish its result: fenced off, nothing changes.
        assertThat(leases.complete(zombie, "videos/" + id + "/360p/g1.mp4", 640, 360, 1)).isFalse();
        assertThat(leases.extend(zombie, LEASE)).as("heartbeat of a fenced-off worker").isFalse();
        assertThat(rows(id).get("360p").getObjectKey()).isEqualTo("videos/" + id + "/360p/g2.mp4");
    }

    @Test
    void heartbeatNoticesTheLostLeaseAndStopsFfmpeg() throws Exception {
        long id = uploadedVideo();
        leases.createPending(id, List.of(new Target("720p", 720)));

        CompletableFuture<Void> job = CompletableFuture.runAsync(() -> processing.process(id));
        // Wait until the worker holds the lease and ffmpeg has started, then take the rendition away from it.
        waitUntil(() -> rows(id).get("720p").getStatus() == VariantStatus.PROCESSING, Duration.ofSeconds(30));
        Thread.sleep(500);
        jdbc.sql("""
                        UPDATE media_variants SET generation = generation + 1, locked_by = 'thief',
                                                  lease_until = now() + interval '1 hour'
                        WHERE media_id = :id AND resolution = '720p'
                        """)
                .param("id", id).update();
        Instant stolen = Instant.now();

        job.get(60, TimeUnit.SECONDS);
        Duration reaction = Duration.between(stolen, Instant.now());

        // Heartbeat every 1 s + ffmpeg polled every 1 s: the worker stops within a few seconds, not after the
        // full transcode, and leaves the rendition (and storage) to the new owner.
        assertThat(reaction).isLessThan(Duration.ofSeconds(4));
        MediaVariantEntity row = rows(id).get("720p");
        assertThat(row.getLockedBy()).isEqualTo("thief");
        assertThat(row.getStatus()).isEqualTo(VariantStatus.PROCESSING);
        assertThat(storage.listKeys("videos/" + id + "/")).isEmpty();
        assertThat(status(id)).isEqualTo(MediaStatus.PROCESSING);
        System.out.println("Worker stopped " + reaction.toMillis() + " ms after losing the lease");
    }

    @Test
    void sweeperRequeuesAVideoWhoseWorkerDied() {
        long id = uploadedVideo();
        media.startProcessing(id);
        leases.createPending(id, List.of(new Target("360p", 360)));
        leases.claimNext(id, "dead-worker", LEASE).orElseThrow();
        expireLeases(id);

        assertThat(processing.requeueExpiredLeases()).isGreaterThanOrEqualTo(1);

        MediaVariantEntity row = rows(id).get("360p");
        assertThat(row.getStatus()).isEqualTo(VariantStatus.PENDING);
        assertThat(row.getGeneration()).as("still fences off the dead worker").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND event_type = 'VideoProcessingRequested'")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);

        // What the re-queued event triggers: the rendition is claimed again (generation 2) and finished.
        processing.process(id);
        assertThat(status(id)).isEqualTo(MediaStatus.READY);
        assertThat(rows(id).get("360p").getGeneration()).isEqualTo(2);
    }

    /** A verified, uploaded video, as complete() leaves it (without the outbox event). */
    private long uploadedVideo() {
        try {
            long size = Files.size(source);
            String key = "media/" + USER_ID + "/lease-it-" + UUID.randomUUID() + ".mp4";
            storage.upload(key, source, "video/mp4");
            Instant now = Instant.now();
            MediaEntity entity = media.save(new MediaEntity(USER_ID, MediaFormat.MP4, size, key, now,
                    now.plusSeconds(3600)));
            media.markVerified(entity.getId(), size, null, now, MediaStatus.PENDING_UPLOAD, MediaStatus.UPLOADED);
            created.add(entity.getId());
            return entity.getId();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void expireLeases(long id) {
        jdbc.sql("UPDATE media_variants SET lease_until = now() - interval '1 second' WHERE media_id = :id "
                        + "AND status = 'PROCESSING'")
                .param("id", id).update();
    }

    private Map<String, MediaVariantEntity> rows(long id) {
        return variants.findByMediaIdOrderByShortSide(id).stream()
                .collect(Collectors.toMap(MediaVariantEntity::getResolution, Function.identity()));
    }

    private MediaStatus status(long id) {
        return media.findById(id).orElseThrow().getStatus();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition reached in time").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
