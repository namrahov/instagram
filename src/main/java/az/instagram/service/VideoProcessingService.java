package az.instagram.service;

import az.instagram.config.TranscodingProperties;
import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaType;
import az.instagram.entity.MediaVariantEntity;
import az.instagram.repository.MediaRepository;
import az.instagram.repository.MediaVariantRepository;
import az.instagram.storage.ObjectStorage;
import az.instagram.transcoding.FfmpegTranscoder;
import az.instagram.transcoding.FfmpegTranscoder.VideoInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Section 4: turns a verified original video into 360p/720p/1080p renditions, outside the upload request.
 * <p>
 * Safe to run more than once for the same video (Kafka delivers at least once, and a crashed worker's job is
 * delivered again):
 * <ul>
 *   <li>a READY or FAILED video is skipped (duplicate event);</li>
 *   <li>a rendition whose row exists is skipped, so a job restarted after a crash continues where it stopped
 *       (e.g. 360p and 720p done, only 1080p is made).</li>
 * </ul>
 * Two workers on the same video at once is prevented by the Kafka key (media id → one partition → one consumer).
 * Per-rendition leases and fencing tokens for the general case are section 7.
 */
@Service
public class VideoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingService.class);

    private final MediaRepository media;
    private final MediaVariantRepository variants;
    private final ObjectStorage storage;
    private final FfmpegTranscoder transcoder;
    private final TranscodingProperties props;

    public VideoProcessingService(MediaRepository media, MediaVariantRepository variants, ObjectStorage storage,
                                  FfmpegTranscoder transcoder, TranscodingProperties props) {
        this.media = media;
        this.variants = variants;
        this.storage = storage;
        this.transcoder = transcoder;
        this.props = props;
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
            Path original = dir.resolve("original");
            storage.download(video.getObjectKey(), original);
            VideoInfo source = transcoder.probe(original);

            Set<String> done = variants.findByMediaIdOrderByHeight(mediaId).stream()
                    .map(MediaVariantEntity::getResolution)
                    .collect(Collectors.toSet());
            for (int height : FfmpegTranscoder.targetHeights(props.heights(), source.shortSide())) {
                String resolution = height + "p";
                if (done.contains(resolution)) {
                    log.info("Media {}: {} already done, skipping", mediaId, resolution);
                    continue;
                }
                long started = System.nanoTime();
                Path rendition = dir.resolve(resolution + ".mp4");
                transcoder.transcode(original, rendition, height);
                VideoInfo produced = transcoder.probe(rendition);

                String key = "videos/" + mediaId + "/" + resolution + ".mp4";
                storage.upload(key, rendition, "video/mp4");
                // Row after file: if we crash in between, the rendition is simply made again next time.
                variants.insertIfAbsent(mediaId, resolution, key, produced.width(), produced.height(),
                        Files.size(rendition));
                log.info("Media {}: {} ({}x{}) done in {} ms", mediaId, resolution, produced.width(),
                        produced.height(), (System.nanoTime() - started) / 1_000_000);
            }

            media.markReady(mediaId, Instant.now());
            log.info("Media {} is READY", mediaId);
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
}
