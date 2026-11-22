package az.instagram.transcoding;

import az.instagram.config.TranscodingProperties;
import az.instagram.exception.InvalidVideoException;
import az.instagram.exception.LeaseLostException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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
                file.resolveSibling(file.getFileName() + ".probe.log"), true, () -> true);
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
     * Writes one HLS rendition into {@code outDir} (section 8): {@code index.m3u8} plus {@value #SEGMENT_SECONDS}-second
     * H.264/AAC segments, shorter side {@code shortSide} pixels ("720p" means 720 on the short side, so a portrait
     * video stays portrait).
     * <p>
     * A keyframe is forced every {@value #SEGMENT_SECONDS} seconds (and none on scene cuts), so every rendition is cut
     * at the same timestamps and the player can switch quality at any segment boundary. CRF keeps quality even;
     * maxrate caps the bitrate, so a rendition really fits the bandwidth the master playlist promises.
     *
     * @param keepGoing checked every second while ffmpeg runs; when it turns false (the lease was lost) ffmpeg is
     *                  killed and {@link LeaseLostException} is thrown, so a fenced-off worker stops burning CPU
     * @return the media playlist
     */
    public Path transcodeHls(Path source, Path outDir, int shortSide, BooleanSupplier keepGoing) {
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String scale = "scale='if(gt(iw,ih),-2,%1$d)':'if(gt(iw,ih),%1$d,-2)'".formatted(shortSide);
        int maxKbps = maxBitrateKbps(shortSide);
        // Runs inside outDir with relative names, so the playlist refers to its segments as "seg_00000.ts".
        run(List.of(props.ffmpegPath(), "-y", "-hide_banner", "-loglevel", "error",
                        "-i", source.toString(),
                        "-vf", scale,
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                        "-maxrate", maxKbps + "k", "-bufsize", 2 * maxKbps + "k",
                        "-force_key_frames", "expr:gte(t,n_forced*" + SEGMENT_SECONDS + ")", "-sc_threshold", "0",
                        "-c:a", "aac", "-b:a", "128k", "-ac", "2",
                        "-f", "hls", "-hls_time", String.valueOf(SEGMENT_SECONDS), "-hls_playlist_type", "vod",
                        "-hls_flags", "independent_segments", "-hls_segment_type", "mpegts",
                        "-hls_segment_filename", "seg_%05d.ts",
                        HlsPlaylists.MEDIA_PLAYLIST),
                outDir, outDir.resolveSibling(outDir.getFileName() + ".log"), false, keepGoing);
        return outDir.resolve(HlsPlaylists.MEDIA_PLAYLIST);
    }

    /** Segment length: 4 s is a common VOD choice (quick start and quick quality switches, not too many files). */
    static final int SEGMENT_SECONDS = 4;

    /** Upper bound of the video bitrate per rendition, roughly the usual H.264 ladder. */
    static int maxBitrateKbps(int shortSide) {
        if (shortSide <= 240) {
            return 400;
        }
        if (shortSide <= 360) {
            return 900;
        }
        if (shortSide <= 480) {
            return 1_500;
        }
        if (shortSide <= 720) {
            return 3_000;
        }
        if (shortSide <= 1080) {
            return 5_500;
        }
        return 12_000;
    }

    private String run(List<String> command, Path log, boolean invalidInputOnFailure, BooleanSupplier keepGoing) {
        return run(command, null, log, invalidInputOnFailure, keepGoing);
    }

    /** Runs a command, returns its stdout. Output goes to a file, so a chatty process can never block on a full pipe. */
    private String run(List<String> command, Path directory, Path log, boolean invalidInputOnFailure,
                       BooleanSupplier keepGoing) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + command.getFirst(), e);
        }
        try {
            long deadline = System.nanoTime() + props.timeout().toNanos();
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                if (!keepGoing.getAsBoolean()) {
                    process.destroyForcibly();
                    throw new LeaseLostException("Lease lost, " + Path.of(command.getFirst()).getFileName() + " stopped");
                }
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    throw new IllegalStateException(command.getFirst() + " timed out after " + props.timeout());
                }
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
