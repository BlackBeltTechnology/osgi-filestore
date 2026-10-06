package hu.blackbelt.osgi.filestore.s3.fixture;

import com.github.davidmoten.aws.lw.client.Client;
import com.github.davidmoten.aws.lw.client.HttpMethod;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Configuration holder for the opt-in Google Cloud Storage interoperability test.
 *
 * <p>Unlike {@link MinioFixture} this fixture starts no container and <strong>does not create the
 * bucket</strong>: creating a bucket over the Cloud Storage XML API requires an
 * {@code x-goog-project-id} header that {@code aws-lightweight-client-java} does not send. The
 * bucket is therefore a precondition — see {@code docs/gcs/setup.md}.
 *
 * <p>All values come from {@link DotEnv}, i.e. {@code filestore-s3/.env} or real environment
 * variables.
 */
@Slf4j
@Getter
public class GcsFixture {

    public static final String KEY_ENABLED = "GCS_TEST_ENABLED";
    public static final String KEY_BUCKET = "GCS_BUCKET_NAME";
    public static final String KEY_ENDPOINT = "GCS_ENDPOINT";
    public static final String KEY_ACCESS = "GCS_ACCESS_KEY";
    public static final String KEY_SECRET = "GCS_SECRET_KEY";
    public static final String KEY_REGION = "GCS_REGION";
    public static final String KEY_PROTOCOL = "GCS_PROTOCOL";

    public static final String DEFAULT_ENDPOINT = "https://storage.googleapis.com";
    public static final String DEFAULT_REGION = "us-east-1";
    public static final String DEFAULT_PROTOCOL = "gcsstore";

    private static final String SETUP_HINT =
            "See docs/gcs/setup.md for how to provision the bucket and HMAC key.";

    private final String bucketName = DotEnv.get(KEY_BUCKET);
    private final String endpoint = DotEnv.get(KEY_ENDPOINT, DEFAULT_ENDPOINT);
    private final String accessKey = DotEnv.get(KEY_ACCESS);
    private final String secretKey = DotEnv.get(KEY_SECRET);
    private final String region = DotEnv.get(KEY_REGION, DEFAULT_REGION);
    private final String protocol = DotEnv.get(KEY_PROTOCOL, DEFAULT_PROTOCOL);

    /** @return true when the test is switched on and every mandatory value is present. */
    public static boolean isConfigured() {
        return DotEnv.isEnabled(KEY_ENABLED)
                && isPresent(DotEnv.get(KEY_BUCKET))
                && isPresent(DotEnv.get(KEY_ACCESS))
                && isPresent(DotEnv.get(KEY_SECRET));
    }

    private static boolean isPresent(final String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Verifies the configured bucket is reachable with the configured credentials.
     *
     * @throws IllegalStateException with an actionable message when it is not
     */
    public void verifyBucketReachable() {
        try {
            client().path(bucketName).method(HttpMethod.GET).query("max-keys", "1").execute();
            log.info("GCS bucket {} reachable at {}", bucketName, endpoint);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot reach GCS bucket '" + bucketName + "' at " + endpoint
                            + ". The bucket must already exist and the HMAC key must have "
                            + "roles/storage.objectAdmin on it. " + SETUP_HINT, e);
        }
    }

    /** @return a client built the same way {@code S3FileStoreService.activate} builds it. */
    public Client client() {
        final String base = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        return Client.s3()
                .region(region)
                .accessKey(accessKey)
                .secretKey(secretKey)
                .baseUrlFactory((service, reg) -> base)
                .build();
    }

    /** Deletes a single object. Used for per-test cleanup; never touches anything else. */
    public void deleteObject(final String key) {
        try {
            client().path(bucketName, key).method(HttpMethod.DELETE).execute();
        } catch (RuntimeException e) {
            log.warn("Could not delete test object {}/{}: {}", bucketName, key, e.getMessage());
        }
    }
}
