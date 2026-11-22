package az.instagram.dto.response;

import az.instagram.service.MediaUploadService.UploadProgress;

import java.time.Instant;
import java.util.List;

/** Resume info, read from storage: upload {@code missingParts}, then call complete. */
public record UploadProgressResponse(
        long mediaId,
        long partSizeBytes,
        int partCount,
        List<Integer> uploadedParts,
        List<Integer> missingParts,
        Instant expiresAt
) {
    public static UploadProgressResponse from(UploadProgress progress) {
        return new UploadProgressResponse(
                progress.media().getId(),
                progress.media().getPartSizeBytes(),
                progress.media().getPartCount(),
                progress.uploadedParts().stream().map(p -> p.partNumber()).toList(),
                progress.missingParts(),
                progress.media().getUploadExpiresAt());
    }
}
