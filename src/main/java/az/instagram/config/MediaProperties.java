package az.instagram.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * @param multipartThreshold  files larger than this are uploaded in parts; smaller ones with a single PUT
 * @param multipartPartSize   size of each part except the last; S3 requires at least 5 MiB
 * @param multipartSessionTtl how long a multipart upload may take in total (part URLs are re-issued meanwhile)
 */
@Validated
@ConfigurationProperties("instagram.media")
public record MediaProperties(
        @NotNull Duration uploadUrlTtl,
        @NotNull Duration downloadUrlTtl,
        @NotNull DataSize maxImageSize,
        @NotNull DataSize maxVideoSize,
        @NotNull DataSize multipartThreshold,
        @NotNull DataSize multipartPartSize,
        @NotNull Duration multipartSessionTtl,
        @NotNull @Valid Cleanup cleanup
) {

    /** S3 rejects a multipart upload whose non-last parts are smaller than this. */
    public static final DataSize S3_MIN_PART_SIZE = DataSize.ofMegabytes(5);

    public MediaProperties {
        if (multipartPartSize != null && multipartPartSize.compareTo(S3_MIN_PART_SIZE) < 0) {
            throw new IllegalArgumentException("instagram.media.multipart-part-size must be at least 5MB");
        }
    }

    /**
     * @param grace     extra time after an upload's deadline before it is discarded, so a client that
     *                  uploaded just in time can still call complete
     * @param batchSize uploads discarded per run
     */
    public record Cleanup(@NotNull Duration grace, @Positive int batchSize) {
    }
}
