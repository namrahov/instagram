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
 * @param ffmpegPath  ffmpeg executable (on PATH by default)
 * @param ffprobePath ffprobe executable (on PATH by default)
 * @param workDir     where originals and renditions are kept while a job runs; emptied after each job
 * @param heights     rendition heights, e.g. 360, 720, 1080. Heights above the source are skipped (no upscaling)
 * @param timeout     one ffmpeg run is killed after this long
 */
@Validated
@ConfigurationProperties("instagram.transcoding")
public record TranscodingProperties(
        @NotBlank String ffmpegPath,
        @NotBlank String ffprobePath,
        @NotNull Path workDir,
        @NotEmpty List<Integer> heights,
        @NotNull Duration timeout
) {
}
