package az.instagram.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties props) {
        return S3Client.builder()
                .endpointOverride(props.endpoint())
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                // MinIO serves buckets as http://host/bucket/key, not http://bucket.host/key.
                .forcePathStyle(true)
                // Newer SDKs add CRC checksums to every request by default; only send them where S3 requires.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .build();
    }

    /** Signs URLs only; it never sends a request, so it uses the client-facing address. */
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .endpointOverride(props.publicEndpoint())
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static StaticCredentialsProvider credentials(StorageProperties props) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }
}
