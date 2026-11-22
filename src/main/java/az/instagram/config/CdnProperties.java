package az.instagram.config;

import java.net.URI;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Section 8: where clients fetch video playlists and segments. Locally the nginx cache from docker-compose; in
 * production a CDN host (e.g. https://cdn.example.com) whose origin is the storage bucket.
 *
 * @param baseUrl the CDN host; {@code videos/...} keys are appended to it
 */
@Validated
@ConfigurationProperties("instagram.cdn")
public record CdnProperties(@NotNull URI baseUrl) {
}
