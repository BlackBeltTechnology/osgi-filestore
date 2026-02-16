package hu.blackbelt.osgi.filestore.s3.fixture;

import com.github.davidmoten.aws.lw.client.Client;
import com.github.davidmoten.aws.lw.client.HttpMethod;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.MinIOContainer;

@Slf4j
public class MinioFixture {

    public static final String DEFAULT_ACCESS_KEY = "minioadmin";
    public static final String DEFAULT_SECRET_KEY = "minioadmin";
    public static final String DEFAULT_BUCKET_NAME = "test-filestore";
    public static final String DEFAULT_REGION = "us-east-1";

    private MinIOContainer minioContainer;

    @Getter
    private Client s3Client;

    @Getter
    private String bucketName = DEFAULT_BUCKET_NAME;

    @Getter
    private String endpoint;

    @Getter
    private String accessKey = DEFAULT_ACCESS_KEY;

    @Getter
    private String secretKey = DEFAULT_SECRET_KEY;

    @Getter
    private String region = DEFAULT_REGION;

    public void setupMinio() {
        minioContainer = new MinIOContainer("minio/minio:latest")
                .withUserName(DEFAULT_ACCESS_KEY)
                .withPassword(DEFAULT_SECRET_KEY);
        minioContainer.start();

        endpoint = minioContainer.getS3URL();
        String endpointUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";

        s3Client = Client.s3()
                .region(region)
                .accessKey(accessKey)
                .secretKey(secretKey)
                .baseUrlFactory((svc, rgn) -> endpointUrl)
                .build();

        s3Client.path(bucketName).method(HttpMethod.PUT).execute();

        log.info("MinIO container started at endpoint: {}", endpoint);
    }

    public void teardownMinio() {
        s3Client = null;
        if (minioContainer != null && minioContainer.isRunning()) {
            minioContainer.stop();
        }
    }
}
