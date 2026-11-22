package az.instagram.storage;

import az.instagram.config.StorageProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** Thin wrapper over the S3 API, so the rest of the code never sees SDK types. */
@Component
public class ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = props.bucket();
    }

    /**
     * URL that allows exactly one thing: PUT of this key, with this content type and length, until it expires.
     * Content-Type and Content-Length are part of the signature, so the client cannot change them.
     */
    public PresignedUpload presignPut(String key, String contentType, long contentLength, Duration ttl) {
        PresignedPutObjectRequest presigned = presigner.presignPutObject(r -> r
                .signatureDuration(ttl)
                .putObjectRequest(p -> p.bucket(bucket).key(key).contentType(contentType).contentLength(contentLength)));

        // The client must send the signed headers as they are. Host is set by its HTTP library.
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUpload(presigned.url().toString(), presigned.httpRequest().method().name(), headers,
                presigned.expiration());
    }

    public String presignGet(String key, Duration ttl) {
        return presigner.presignGetObject(r -> r
                        .signatureDuration(ttl)
                        .getObjectRequest(g -> g.bucket(bucket).key(key)))
                .url().toString();
    }

    /** What storage really holds under this key, or empty if nothing was uploaded. */
    public Optional<StoredObject> head(String key) {
        try {
            HeadObjectResponse head = s3.headObject(h -> h.bucket(bucket).key(key));
            return Optional.of(new StoredObject(head.contentLength(), head.contentType(), stripQuotes(head.eTag())));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void delete(String key) {
        s3.deleteObject(d -> d.bucket(bucket).key(key));
    }

    private static String stripQuotes(String etag) {
        return etag == null ? null : etag.replace("\"", "");
    }

    public record PresignedUpload(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    public record StoredObject(long sizeBytes, String contentType, String etag) {
    }
}
