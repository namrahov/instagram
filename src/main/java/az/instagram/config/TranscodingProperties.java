package az.instagram.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param ffmpegPath        ffmpeg executable (on PATH by default)
 * @param ffprobePath       ffprobe executable (on PATH by default)
 * @param workDir           where originals and renditions are kept while a job runs; emptied after each job
 * @param heights           rendition heights, e.g. 360, 720, 1080. Heights above the source are skipped (no upscaling)
 * @param timeout           one ffmpeg run is killed after this long
 * @param leaseDuration     how long a claimed rendition stays ours without a heartbeat (section 7)
 * @param heartbeatInterval how often a running job extends its lease; several beats must fit in one lease
 * @param sweeperGrace      how long after a lease ran out the sweeper re-queues the video, so a late heartbeat
 *                          (GC pause, DB blip) is not mistaken for a dead worker
 */
@Validated
@ConfigurationProperties("instagram.transcoding")
public record TranscodingProperties(
        @NotBlank String ffmpegPath,
        @NotBlank String ffprobePath,
        @NotNull Path workDir,
        @NotEmpty List<Integer> heights,
        @NotNull Duration timeout,
        @NotNull Duration leaseDuration,
        @NotNull Duration heartbeatInterval,
        @NotNull Duration sweeperGrace
) {
    public TranscodingProperties {
        if (leaseDuration != null && heartbeatInterval != null
                && heartbeatInterval.multipliedBy(2).compareTo(leaseDuration) > 0) {
            throw new IllegalArgumentException(
                    "instagram.transcoding.heartbeat-interval must be at most half of lease-duration");
        }
    }
}
