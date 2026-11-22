package az.instagram.storage;

import az.instagram.config.StorageProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.presigner.PresignedRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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
        return toUpload(presigner.presignPutObject(r -> r
                .signatureDuration(ttl)
                .putObjectRequest(p -> p.bucket(bucket).key(key).contentType(contentType).contentLength(contentLength))));
    }

    // ---- Multipart upload (section 3) ----

    /** Opens a multipart upload session. The content type is fixed here, by the backend, for the final object. */
    public String createMultipartUpload(String key, String contentType) {
        return s3.createMultipartUpload(c -> c.bucket(bucket).key(key).contentType(contentType)).uploadId();
    }

    /** URL for one part only: this part number of this upload, with exactly this length, until it expires. */
    public PresignedUpload presignUploadPart(String key, String uploadId, int partNumber, long contentLength,
                                             Duration ttl) {
        return toUpload(presigner.presignUploadPart(r -> r
                .signatureDuration(ttl)
                .uploadPartRequest(u -> u.bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(partNumber).contentLength(contentLength))));
    }

    /**
     * Parts storage already has for this upload, sorted by part number.
     * Empty if the upload session no longer exists (completed or aborted).
     */
    public Optional<List<UploadedPart>> listParts(String key, String uploadId) {
        try {
            List<UploadedPart> parts = new ArrayList<>();
            // Paginated: one page holds at most 1000 parts.
            for (Part part : s3.listPartsPaginator(l -> l.bucket(bucket).key(key).uploadId(uploadId)).parts()) {
                parts.add(new UploadedPart(part.partNumber(), part.size(), stripQuotes(part.eTag())));
            }
            parts.sort(Comparator.comparingInt(UploadedPart::partNumber));
            return Optional.of(parts);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Joins the parts into one object. After this the upload id is gone and the object is visible. */
    public void completeMultipartUpload(String key, String uploadId, List<UploadedPart> parts) {
        List<CompletedPart> completed = parts.stream()
                .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.etag()).build())
                .toList();
        s3.completeMultipartUpload(c -> c.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(m -> m.parts(completed)));
    }

    /** Frees the space taken by uploaded parts. No-op if the upload is already gone. */
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            s3.abortMultipartUpload(a -> a.bucket(bucket).key(key).uploadId(uploadId));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
        }
    }

    private static PresignedUpload toUpload(PresignedRequest presigned) {
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

    /** Streams the object to a local file (which must not exist yet). Used by workers, not by the API. */
    public void download(String key, Path target) {
        s3.getObject(g -> g.bucket(bucket).key(key), ResponseTransformer.toFile(target));
    }

    public void upload(String key, Path source, String contentType) {
        s3.putObject(p -> p.bucket(bucket).key(key).contentType(contentType), RequestBody.fromFile(source));
    }

    private static String stripQuotes(String etag) {
        return etag == null ? null : etag.replace("\"", "");
    }

    public record PresignedUpload(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    public record StoredObject(long sizeBytes, String contentType, String etag) {
    }

    public record UploadedPart(int partNumber, long sizeBytes, String etag) {
    }
}
