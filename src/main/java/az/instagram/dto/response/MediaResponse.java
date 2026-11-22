package az.instagram.dto.response;

import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaStatus;
import az.instagram.entity.MediaType;

import java.time.Instant;

/** {@code downloadUrl} is a short-lived presigned GET URL, present only once the upload is verified. */
public record MediaResponse(
        long id,
        MediaType mediaType,
        String contentType,
        MediaStatus status,
        Long sizeBytes,
        Instant createdAt,
        Instant uploadedAt,
        String downloadUrl
) {
    public static MediaResponse from(MediaEntity media, String downloadUrl) {
        return new MediaResponse(
                media.getId(),
                media.getMediaType(),
                media.getContentType(),
                media.getStatus(),
                media.getSizeBytes(),
                media.getCreatedAt(),
                media.getUploadedAt(),
                downloadUrl);
    }
}
