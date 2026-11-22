package az.instagram.dto.response;

import az.instagram.service.MediaUploadService.InitiatedUpload;

import java.time.Instant;
import java.util.Map;

/**
 * Tells the client where and how to upload. It must send exactly {@code method} to {@code uploadUrl}
 * with {@code headers}, then call {@code POST /media/uploads/{mediaId}/complete}.
 */
public record InitiateUploadResponse(
        long mediaId,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt
) {
    public static InitiateUploadResponse from(InitiatedUpload initiated) {
        return new InitiateUploadResponse(
                initiated.media().getId(),
                initiated.upload().url(),
                initiated.upload().method(),
                initiated.upload().headers(),
                initiated.upload().expiresAt());
    }
}
