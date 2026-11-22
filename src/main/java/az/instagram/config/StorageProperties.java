package az.instagram.config;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3-compatible object storage (MinIO locally, S3 in production).
 *
 * @param endpoint       address the backend uses to reach the storage
 * @param publicEndpoint address presigned URLs are signed for; the client must be able to reach it
 */
@Validated
@ConfigurationProperties("instagram.storage")
public record StorageProperties(
        @NotNull URI endpoint,
        @NotNull URI publicEndpoint,
        @NotBlank String region,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String bucket
) {
}
