package az.instagram.dto.response;

import az.instagram.entity.MediaEntity;
import az.instagram.service.MediaUploadService.InitiatedUpload;
import az.instagram.storage.ObjectStorage.PresignedUpload;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tells the client how to upload. The server picks the mode from the file size.
 * <ul>
 *   <li>{@code SINGLE}: send {@code method} to {@code uploadUrl} with {@code headers}, then call complete.</li>
 *   <li>{@code MULTIPART}: split the file into {@code partCount} parts of {@code partSizeBytes} (the last one holds
 *       the rest), get their URLs from {@code POST /media/uploads/{mediaId}/parts/urls}, upload, then call complete.
 *       {@code expiresAt} is the end of the whole upload session.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InitiateUploadResponse(
        long mediaId,
        UploadMode uploadMode,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        Long partSizeBytes,
        Integer partCount,
        Instant expiresAt
) {
    public enum UploadMode {
        SINGLE,
        MULTIPART
    }

    public static InitiateUploadResponse from(InitiatedUpload initiated) {
        MediaEntity media = initiated.media();
        PresignedUpload upload = initiated.upload();
        if (upload == null) {
            return new InitiateUploadResponse(media.getId(), UploadMode.MULTIPART, null, null, null,
                    media.getPartSizeBytes(), media.getPartCount(), media.getUploadExpiresAt());
        }
        return new InitiateUploadResponse(media.getId(), UploadMode.SINGLE, upload.url(), upload.method(),
                upload.headers(), null, null, upload.expiresAt());
    }
}
