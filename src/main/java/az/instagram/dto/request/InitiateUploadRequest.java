package az.instagram.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record InitiateUploadRequest(
        @NotBlank String contentType,
        @Positive long sizeBytes
) {
}
