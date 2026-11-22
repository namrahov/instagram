package az.instagram.dto.response;

import az.instagram.service.MediaUploadService.PartUrl;

import java.time.Instant;
import java.util.Map;

/** Send {@code method} to {@code url} with exactly {@code headers}; the response's ETag header confirms the part. */
public record PartUrlResponse(
        int partNumber,
        String url,
        String method,
        Map<String, String> headers,
        Instant expiresAt
) {
    public static PartUrlResponse from(PartUrl part) {
        return new PartUrlResponse(part.partNumber(), part.upload().url(), part.upload().method(),
                part.upload().headers(), part.upload().expiresAt());
    }
}
