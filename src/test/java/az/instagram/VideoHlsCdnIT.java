package az.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.instagram.config.TranscodingProperties;
import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaFormat;
import az.instagram.entity.MediaStatus;
import az.instagram.repository.MediaRepository;
import az.instagram.service.MediaUploadService;
import az.instagram.service.VideoProcessingService;
import az.instagram.storage.ObjectStorage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Section 8 against the real PostgreSQL, MinIO, ffmpeg and the nginx CDN from docker-compose.yml
 * ({@code docker compose up -d} first). The Kafka worker and the outbox publisher are off: the tests call the
 * processing service directly.
 */
@SpringBootTest(properties = {
        "instagram.video-worker.enabled=false",
        "instagram.outbox.publisher.enabled=false",
        "instagram.transcoding.heights=360,720",
        "instagram.transcoding.sweeper-interval=PT1H",
})
class VideoHlsCdnIT {

    private static final long USER_ID = 999_998;
    /** 10 s, 720p, with sound: 3 segments of at most 4 s per rendition. */
    private static Path source;

    @Autowired VideoProcessingService processing;
    @Autowired MediaUploadService uploads;
    @Autowired MediaRepository media;
    @Autowired ObjectStorage storage;
    @Autowired JdbcClient jdbc;
    @Autowired TranscodingProperties props;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void makeSourceVideo() throws Exception {
        if (source != null) {
            return;
        }
        source = Files.createTempFile("hls-it-", ".mp4");
        Process ffmpeg = new ProcessBuilder(props.ffmpegPath(), "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=duration=10:size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                source.toString())
                .inheritIO().start();
        assertThat(ffmpeg.waitFor(60, TimeUnit.SECONDS) && ffmpeg.exitValue() == 0).as("test video created").isTrue();
    }

    @AfterEach
    void cleanUp() {
        for (long id : created) {
            storage.deletePrefix("videos/" + id + "/");
            media.findById(id).ifPresent(m -> storage.delete(m.getObjectKey()));
            jdbc.sql("DELETE FROM media_variants WHERE media_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM outbox WHERE aggregate_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM media WHERE id = :id").param("id", id).update();
        }
    }

    @Test
    void readyVideoIsAnHlsLadderServedAndCachedByTheCdn() throws Exception {
        long id = uploadedVideo();
        processing.process(id);

        MediaEntity video = media.findById(id).orElseThrow();
        assertThat(video.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(video.getActiveVersion()).isEqualTo(1);
        String playbackUrl = uploads.playbackUrl(video).orElseThrow();
        assertThat(playbackUrl).isEqualTo("http://localhost:8090/videos/" + id + "/v1/master.m3u8");

        // Master: one entry per rendition, lowest bandwidth first, relative paths.
        HttpResponse<String> master = get(playbackUrl);
        assertThat(master.statusCode()).isEqualTo(200);
        assertThat(master.headers().firstValue("Content-Type")).hasValue("application/vnd.apple.mpegurl");
        assertThat(master.headers().firstValue("Cache-Control")).hasValue("public, max-age=31536000, immutable");
        assertThat(master.headers().firstValue("X-Cache-Status")).hasValue("MISS");
        List<String> lines = master.body().lines().toList();
        assertThat(lines.getFirst()).isEqualTo("#EXTM3U");
        assertThat(lines).filteredOn(l -> l.startsWith("#EXT-X-STREAM-INF")).hasSize(2)
                .first().asString().contains("RESOLUTION=640x360");
        assertThat(lines).contains("360p/g1/index.m3u8", "720p/g1/index.m3u8");
        assertThat(bandwidth(lines, 0)).isLessThan(bandwidth(lines, 1));

        // Second request: served by the edge, the origin is not asked.
        assertThat(get(playbackUrl).headers().firstValue("X-Cache-Status")).hasValue("HIT");

        // Rendition playlist and a segment, resolved like a player does.
        URI p720 = URI.create(playbackUrl).resolve("720p/g1/index.m3u8");
        HttpResponse<String> media720 = get(p720.toString());
        assertThat(media720.body()).contains("#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-ENDLIST");
        String firstSegment = media720.body().lines().filter(l -> l.endsWith(".ts")).findFirst().orElseThrow();
        HttpResponse<String> segment = get(p720.resolve(firstSegment).toString());
        assertThat(segment.statusCode()).isEqualTo(200);
        assertThat(segment.headers().firstValue("Content-Type")).hasValue("video/mp2t");
        assertThat(media720.body().lines().filter(l -> l.endsWith(".ts")).count()).isEqualTo(3);

        assertThat(uploads.variants(video)).extracting(v -> v.getResolution()).containsExactly("360p", "720p");
        assertThat(uploads.variants(video)).allSatisfy(v -> assertThat(v.getPeakBitrate()).isPositive());
    }

    @Test
    void reprocessingWritesANewVersionAndSwitchesPlaybackOnlyWhenItIsReady() throws Exception {
        long id = uploadedVideo();
        processing.process(id);
        String v1 = uploads.playbackUrl(media.findById(id).orElseThrow()).orElseThrow();
        assertThat(get(v1).statusCode()).isEqualTo(200);

        MediaEntity requested = processing.requestReprocessing(USER_ID, id);
        assertThat(requested.getStatus()).isEqualTo(MediaStatus.UPLOADED);
        assertThat(requested.getTargetVersion()).isEqualTo(2);
        // v2 is not ready: clients keep playing v1.
        assertThat(requested.getActiveVersion()).isEqualTo(1);
        assertThat(uploads.playbackUrl(requested)).hasValue(v1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);

        // One job per video at a time; and not someone else's video.
        assertThatThrownBy(() -> processing.requestReprocessing(USER_ID, id))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThatThrownBy(() -> processing.requestReprocessing(USER_ID + 1, id))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");

        processing.process(id);
        MediaEntity done = media.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(done.getActiveVersion()).isEqualTo(2);
        String v2 = uploads.playbackUrl(done).orElseThrow();
        assertThat(v2).endsWith("/videos/" + id + "/v2/master.m3u8");
        assertThat(get(v2).statusCode()).isEqualTo(200);
        // The old version stays: a player in the middle of v1, or an edge that cached its master, still works.
        assertThat(get(v1).statusCode()).isEqualTo(200);
        assertThat(storage.listKeys("videos/" + id + "/v1/")).contains("videos/" + id + "/v1/master.m3u8");
    }

    private long uploadedVideo() throws Exception {
        long size = Files.size(source);
        String key = "media/" + USER_ID + "/hls-it-" + UUID.randomUUID() + ".mp4";
        storage.upload(key, source, "video/mp4");
        Instant now = Instant.now();
        MediaEntity entity = media.save(new MediaEntity(USER_ID, MediaFormat.MP4, size, key, now,
                now.plusSeconds(3600)));
        media.markVerified(entity.getId(), size, null, now, MediaStatus.PENDING_UPLOAD, MediaStatus.UPLOADED);
        created.add(entity.getId());
        return entity.getId();
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private static long bandwidth(List<String> masterLines, int index) {
        String line = masterLines.stream().filter(l -> l.startsWith("#EXT-X-STREAM-INF")).toList().get(index);
        return Long.parseLong(line.replaceAll(".*[:,]BANDWIDTH=(\\d+).*", "$1"));
    }
}
