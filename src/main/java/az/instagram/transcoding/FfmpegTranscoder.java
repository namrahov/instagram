package az.instagram.transcoding;

import az.instagram.config.TranscodingProperties;
import az.instagram.exception.InvalidVideoException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the ffprobe/ffmpeg command-line tools, as a production worker container would.
 * CPU-heavy and slow, which is why it runs in a Kafka worker and never inside an HTTP request.
 */
@Component
public class FfmpegTranscoder {

    private static final Logger LOG = LoggerFactory.getLogger(FfmpegTranscoder.class);

    private final TranscodingProperties props;

    public FfmpegTranscoder(TranscodingProperties props) {
        this.props = props;
    }

    /**
     * Reads the first video stream's frame size, as displayed (rotation applied).
     *
     * @throws InvalidVideoException if the file has no readable video stream
     */
    public VideoInfo probe(Path file) {
        String output = run(List.of(props.ffprobePath(), "-v", "error", "-select_streams", "v:0",
                        "-show_entries", "stream=width,height:stream_side_data=rotation", "-of", "csv=p=0:s=,",
                        file.toString()),
                file.resolveSibling(file.getFileName() + ".probe.log"), true);
        String[] fields = output.strip().split("[,\\s]+");
        if (fields.length < 2 || fields[0].isEmpty()) {
            throw new InvalidVideoException("No video stream found");
        }
        int width = Integer.parseInt(fields[0]);
        int height = Integer.parseInt(fields[1]);
        // Phones store portrait video as landscape frames plus a rotation flag; ffmpeg applies it when transcoding.
        boolean rotated = fields.length > 2 && Math.abs(Integer.parseInt(fields[2])) % 180 == 90;
        return rotated ? new VideoInfo(height, width) : new VideoInfo(width, height);
    }

    /**
     * Writes an H.264/AAC MP4 whose shorter side is {@code shortSide} pixels ("720p" means 720 on the short side, so
     * a portrait video stays portrait). {@code +faststart} puts the index first so playback can start before the
     * whole file is downloaded.
     */
    public void transcode(Path source, Path target, int shortSide) {
        String scale = "scale='if(gt(iw,ih),-2,%1$d)':'if(gt(iw,ih),%1$d,-2)'".formatted(shortSide);
        run(List.of(props.ffmpegPath(), "-y", "-hide_banner", "-loglevel", "error",
                        "-i", source.toString(),
                        "-vf", scale,
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "128k",
                        "-movflags", "+faststart",
                        target.toString()),
                target.resolveSibling(target.getFileName() + ".log"), false);
    }

    /** Runs a command, returns its stdout. Output goes to a file, so a chatty process can never block on a full pipe. */
    private String run(List<String> command, Path log, boolean invalidInputOnFailure) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + command.getFirst(), e);
        }
        try {
            if (!process.waitFor(props.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(command.getFirst() + " timed out after " + props.timeout());
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                // The details (paths, codec errors) go to the log only; the message ends up in the API's failureReason.
                String tool = Path.of(command.getFirst()).getFileName().toString();
                LOG.warn("{} exited with {}: {}", tool, process.exitValue(), tail(output));
                // ffprobe failing on the original means the upload is not a video: no point in retrying.
                throw invalidInputOnFailure
                        ? new InvalidVideoException("Not a playable video file")
                        : new IllegalStateException(tool + " exited with " + process.exitValue());
            }
            return output;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command.getFirst(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= 200 ? trimmed : "..." + trimmed.substring(trimmed.length() - 200);
    }

    /** Frame size as displayed. */
    public record VideoInfo(int width, int height) {
        public int shortSide() {
            return Math.min(width, height);
        }
    }

    /** Heights to produce for a source: the configured ones that do not upscale; at least one. */
    public static List<Integer> targetHeights(List<Integer> configured, int sourceShortSide) {
        List<Integer> heights = new ArrayList<>();
        for (int h : configured) {
            if (h <= sourceShortSide) {
                heights.add(h);
            }
        }
        if (heights.isEmpty()) {
            // Smaller than every configured size: keep the source size (rounded down to even, as H.264 requires).
            heights.add(sourceShortSide - sourceShortSide % 2);
        }
        return heights;
    }
}
