package az.instagram.dto.response;

import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaStatus;
import az.instagram.entity.MediaType;
import az.instagram.service.MediaUploadService.VariantView;

import java.time.Instant;
import java.util.List;

/**
 * {@code downloadUrl}: short-lived presigned GET URL of the original, present once the upload is verified.
 * {@code variants}: transcoded renditions of a READY video (empty otherwise).
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
        List<Variant> variants
) {
    public record Variant(String resolution, int width, int height, long sizeBytes, String url) {
        static Variant from(VariantView view) {
            var v = view.variant();
            return new Variant(v.getResolution(), v.getWidth(), v.getHeight(), v.getSizeBytes(), view.url());
        }
    }

    public static MediaResponse from(MediaEntity media, String downloadUrl, List<VariantView> variants) {
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
                variants.stream().map(Variant::from).toList());
    }
}
