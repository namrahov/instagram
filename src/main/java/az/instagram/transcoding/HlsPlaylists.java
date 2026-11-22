package az.instagram.transcoding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HLS (section 8): a video is a master playlist that lists one media playlist per rendition; each media playlist
 * lists short segments. The player reads the master, picks a rendition by measured bandwidth and switches between
 * them at segment boundaries (adaptive bitrate).
 * <pre>
 * videos/123/v2/master.m3u8
 * videos/123/v2/360p/g1/index.m3u8, seg_00000.ts, seg_00001.ts, ...
 * videos/123/v2/720p/g1/index.m3u8, ...
 * </pre>
 */
public final class HlsPlaylists {

    public static final String PLAYLIST_TYPE = "application/vnd.apple.mpegurl";
    public static final String SEGMENT_TYPE = "video/mp2t";
    public static final String MEDIA_PLAYLIST = "index.m3u8";
    public static final String MASTER_PLAYLIST = "master.m3u8";

    private HlsPlaylists() {
    }

    /**
     * What one rendition's media playlist adds up to.
     *
     * @param segments    file names, in playback order
     * @param totalBytes  all segments together
     * @param peakBitrate bits per second of the busiest segment (the HLS spec's BANDWIDTH)
     * @param avgBitrate  bits per second over the whole video (AVERAGE-BANDWIDTH)
     */
    public record Measured(List<String> segments, long totalBytes, long peakBitrate, long avgBitrate) {
    }

    /** Reads a media playlist written by ffmpeg and measures its segments, which must be next to it. */
    public static Measured measure(Path playlist) throws IOException {
        Path dir = playlist.getParent();
        List<String> segments = new ArrayList<>();
        long totalBytes = 0;
        double totalSeconds = 0;
        double peak = 0;
        double duration = -1;
        for (String raw : Files.readAllLines(playlist, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("#EXTINF:")) {
                duration = Double.parseDouble(line.substring("#EXTINF:".length()).split(",", 2)[0]);
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                if (duration < 0) {
                    throw new IllegalStateException("Segment without #EXTINF in " + playlist.getFileName());
                }
                long bytes = Files.size(dir.resolve(line));
                segments.add(line);
                totalBytes += bytes;
                totalSeconds += duration;
                if (duration > 0) {
                    peak = Math.max(peak, bytes * 8 / duration);
                }
                duration = -1;
            }
        }
        if (segments.isEmpty() || totalSeconds <= 0) {
            throw new IllegalStateException("No segments in " + playlist.getFileName());
        }
        return new Measured(List.copyOf(segments), totalBytes, Math.round(Math.ceil(peak)),
                Math.round(Math.ceil(totalBytes * 8 / totalSeconds)));
    }

    /** One line of the master playlist. {@code uri} is relative to the master, e.g. {@code 720p/g1/index.m3u8}. */
    public record Entry(String uri, int width, int height, long peakBitrate, long avgBitrate) {
    }

    /**
     * The master playlist. Lowest bandwidth first: players that start with the first entry begin fast and step up.
     * The paths are relative, so the same file works behind any CDN host.
     */
    public static String master(List<Entry> entries) {
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        entries.stream()
                .sorted((a, b) -> Long.compare(a.peakBitrate(), b.peakBitrate()))
                .forEach(e -> m3u8.append(String.format(Locale.ROOT,
                        "#EXT-X-STREAM-INF:BANDWIDTH=%d,AVERAGE-BANDWIDTH=%d,RESOLUTION=%dx%d\n%s\n",
                        e.peakBitrate(), e.avgBitrate(), e.width(), e.height(), e.uri())));
        return m3u8.toString();
    }

    public static String contentType(String fileName) {
        if (fileName.endsWith(".m3u8")) {
            return PLAYLIST_TYPE;
        }
        if (fileName.endsWith(".ts")) {
            return SEGMENT_TYPE;
        }
        return "application/octet-stream";
    }
}
