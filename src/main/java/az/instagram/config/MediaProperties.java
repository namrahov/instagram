package az.instagram.config;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("instagram.media")
public record MediaProperties(
        @NotNull Duration uploadUrlTtl,
        @NotNull Duration downloadUrlTtl,
        @NotNull DataSize maxImageSize,
        @NotNull DataSize maxVideoSize
) {
}
