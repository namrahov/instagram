package az.instagram.entity;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The only content types a client may upload. Anything else is refused before a URL is issued. */
public enum MediaFormat {
    JPEG("image/jpeg", MediaType.IMAGE, "jpg"),
    PNG("image/png", MediaType.IMAGE, "png"),
    WEBP("image/webp", MediaType.IMAGE, "webp"),
    HEIC("image/heic", MediaType.IMAGE, "heic"),
    MP4("video/mp4", MediaType.VIDEO, "mp4"),
    QUICKTIME("video/quicktime", MediaType.VIDEO, "mov");

    private final String contentType;
    private final MediaType mediaType;
    private final String extension;

    MediaFormat(String contentType, MediaType mediaType, String extension) {
        this.contentType = contentType;
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public static Optional<MediaFormat> ofContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String normalized = contentType.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.contentType.equals(normalized)).findFirst();
    }

    public String contentType() {
        return contentType;
    }

    public MediaType mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }
}
