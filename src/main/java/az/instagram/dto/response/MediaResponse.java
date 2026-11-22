package az.instagram.dto.response;

import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaStatus;
import az.instagram.entity.MediaType;
import az.instagram.entity.MediaVariantEntity;

import java.time.Instant;
import java.util.List;

/**
 * {@code downloadUrl}: short-lived presigned GET URL of the original, present once the upload is verified.
 * {@code playbackUrl}: video only, the HLS master playlist of {@code activeVersion} on the CDN (section 8). The
 * player picks a rendition from it by bandwidth. Present once a version is READY, and kept while a newer
 * version is being produced (status then tells about that newer run).
 * {@code variants}: the renditions in that version.
 * {@code failureReason}: why the upload was rejected or the processing FAILED.
 */
public record MediaResponse(
        long id,
        MediaType mediaType,
        String contentType,
        MediaStatus status,
        Long sizeBytes,
        Instant createdAt,
        Instant uploadedAt,
        Instant processedAt,
        String failureReason,
        String downloadUrl,
        Integer activeVersion,
        String playbackUrl,
        List<Variant> variants
) {
    /** {@code peakBitrate}/{@code avgBitrate} in bits per second. */
    public record Variant(String resolution, int width, int height, long sizeBytes, Long peakBitrate,
                          Long avgBitrate) {
        static Variant from(MediaVariantEntity v) {
            return new Variant(v.getResolution(), v.getWidth(), v.getHeight(), v.getSizeBytes(), v.getPeakBitrate(),
                    v.getAvgBitrate());
        }
    }

    public static MediaResponse from(MediaEntity media, String downloadUrl, String playbackUrl,
                                     List<MediaVariantEntity> variants) {
        return new MediaResponse(
                media.getId(),
                media.getMediaType(),
                media.getContentType(),
                media.getStatus(),
                media.getSizeBytes(),
                media.getCreatedAt(),
                media.getUploadedAt(),
                media.getProcessedAt(),
                media.getFailureReason(),
                downloadUrl,
                media.getActiveVersion(),
                playbackUrl,
                variants.stream().map(Variant::from).toList());
    }
}
