package hu.blackbelt.osgi.filestore.s3;

import com.google.common.io.ByteStreams;
import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.s3.fixture.GcsFixture;
import hu.blackbelt.osgi.filestore.urlhandler.FileStoreUrlStreamHandler;
import org.apache.sling.commons.mime.MimeTypeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.BundleContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in integration test running the {@code FileStoreService} contract against a real Google
 * Cloud Storage bucket over the S3 interoperability (XML) API.
 *
 * <p>Skipped entirely unless {@code GCS_TEST_ENABLED=true} plus bucket and HMAC credentials are
 * available from {@code filestore-s3/.env} or the environment. See
 * {@code filestore-s3/GCS_INTEROP.md}.
 *
 * <p>Unlike {@link S3FileStoreServiceTest}, this test does <strong>not</strong> pre-assign
 * {@code target.s3Client}: it lets {@code activate()} build the client from configuration so the
 * endpoint/credential wiring is genuinely covered.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@EnabledIf("gcsEnabled")
public class GcsS3FileStoreServiceTest {

    private static final String FILENAME = "test.txt";
    private static final String TEXT_PLAIN = "text/plain";
    private static final String CONTENT = "test";
    private static final int MULTIPART_THRESHOLD = 5 * 1024 * 1024; // mirrors S3FileStoreService
    private static final int LARGE_SIZE = 6 * 1024 * 1024;

    static boolean gcsEnabled() {
        return GcsFixture.isConfigured();
    }

    /**
     * Outside OSGi nothing registers the {@code URLStreamHandlerService}, so {@code new URL("gcsstore:...")}
     * would throw {@link java.net.MalformedURLException}. Install the same handler the component registers
     * in {@code activate()} so {@code getAccessUrl()} is exercisable from a plain JUnit run.
     */
    private static final AtomicReference<FileStoreService> SERVICE_REF = new AtomicReference<>();

    @BeforeAll
    static void installUrlStreamHandlerFactory() {
        try {
            URL.setURLStreamHandlerFactory(protocol -> {
                final FileStoreService service = SERVICE_REF.get();
                if (service == null || !protocol.equalsIgnoreCase(service.getProtocol())) {
                    return null;
                }
                // FileStoreUrlStreamHandler extends AbstractURLStreamHandlerService, whose parseURL
                // delegates to an OSGi-supplied `realHandler` that only exists inside a framework.
                // Wrap it in a plain JDK handler so the default parseURL is used and only
                // openConnection is delegated.
                final FileStoreUrlStreamHandler delegate = new FileStoreUrlStreamHandler(service);
                return new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(final URL url) {
                        return delegate.openConnection(url);
                    }
                };
            });
        } catch (Error alreadyInstalled) {
            // only one factory may be set per JVM; a previous test class already did it
        }
    }

    @InjectMocks
    S3FileStoreService target;

    @Mock
    MimeTypeService mimeTypeServiceMock;

    @Mock
    BundleContext context;

    private final GcsFixture fixture = new GcsFixture();
    private final List<String> createdKeys = new ArrayList<>();

    @BeforeEach
    public void setup() {
        fixture.verifyBucketReachable();

        final S3FileStoreService.Config config = mock(S3FileStoreService.Config.class);
        when(config.protocol()).thenReturn(fixture.getProtocol());
        when(config.bucketName()).thenReturn(fixture.getBucketName());
        when(config.endpoint()).thenReturn(fixture.getEndpoint());
        when(config.accessKey()).thenReturn(fixture.getAccessKey());
        when(config.secretKey()).thenReturn(fixture.getSecretKey());
        when(config.region()).thenReturn(fixture.getRegion());

        // deliberately NOT setting target.s3Client — activate() must build it
        target.activate(null, config);
        SERVICE_REF.set(target);
    }

    @AfterEach
    public void cleanup() {
        createdKeys.forEach(fixture::deleteObject);
        createdKeys.clear();
        target.deactivate();
    }

    private String put(final byte[] content, final String fileName, final String mimeType) throws IOException {
        final String fileId = target.put(new ByteArrayInputStream(content), fileName, mimeType);
        createdKeys.add(fileId);
        return fileId;
    }

    // ── activate() -------------------------------------------------------

    @Test
    public void activateBuildsClientFromConfig() {
        assertNotNull(target.s3Client, "activate() must build the S3 client from configuration");
        assertThat(target.getProtocol(), equalTo(fixture.getProtocol().toLowerCase()));
        assertThat(target.bucketName, equalTo(fixture.getBucketName()));
    }

    // ── small file path (putSmallFile) -----------------------------------

    @Test
    public void putAndGetSmallFile() throws IOException {
        final byte[] content = CONTENT.getBytes(StandardCharsets.UTF_8);
        final String fileId = put(content, FILENAME, TEXT_PLAIN);

        assertThat(fileId, notNullValue());
        assertThat(fileId.length(), equalTo(32));
        assertThat(fileId, not(org.hamcrest.Matchers.containsString("-")));
        assertTrue(target.exists(fileId), "stored file must exist");

        try (InputStream in = target.get(fileId)) {
            assertThat(new String(ByteStreams.toByteArray(in), StandardCharsets.UTF_8), equalTo(CONTENT));
        }
    }

    // ── the GCS metadata-prefix regression guard -------------------------

    @Test
    public void metadataRoundTripsWithAmzPrefix() throws IOException {
        final byte[] content = CONTENT.getBytes(StandardCharsets.UTF_8);
        final String fileId = put(content, FILENAME, TEXT_PLAIN);

        // If Google ever switched custom metadata to x-amz-meta- -> x-goog-meta-,
        // aws-lightweight-client-java's Response.metadata() would return an empty map
        // and each of these would fail with NPE / NumberFormatException.
        assertThat(target.getFileName(fileId), equalTo(FILENAME));
        assertThat(target.getMimeType(fileId), equalTo(TEXT_PLAIN));
        assertThat(target.getSize(fileId), equalTo((long) content.length));

        final Date createTime = target.getCreateTime(fileId);
        assertThat(createTime, notNullValue());
        assertThat(createTime.getTime(), greaterThan(0L));
    }

    // ── large file path (putLargeFile / multipart) -----------------------

    /**
     * Files >= {@code MULTIPART_THRESHOLD} (5 MB) go through the multipart upload in
     * {@code S3FileStoreService.putLargeFile}. It deliberately does not use {@code Multipart} from
     * aws-lightweight-client-java, which deviates from the S3 specification in two ways Amazon S3 and
     * MinIO tolerate and GCS does not: no {@code Content-Length} on the {@code POST ?uploads} initiate
     * (GCS answers HTTP 411) and a malformed {@code xmlns} on the complete document (GCS answers HTTP
     * 400 {@code MalformedCompleteMultipartUploadRequest}). Both are guarded, without a network, by
     * {@code S3FileStoreServiceMultipartWireTest}.
     */
    @Test
    public void putAndGetLargeFileViaMultipart() throws IOException {
        final byte[] content = patterned(LARGE_SIZE);
        final String fileId = put(content, "big.bin", "application/octet-stream");

        assertTrue(target.exists(fileId), "multipart-uploaded file must exist");

        // putLargeFile() does NOT write the `size` metadata entry; getSize() must
        // resolve it through the __content-length__ fallback instead.
        assertThat(target.getSize(fileId), equalTo((long) LARGE_SIZE));

        // metadata set on the initiate request must survive assembly of the parts
        assertThat(target.getFileName(fileId), equalTo("big.bin"));
        assertThat(target.getMimeType(fileId), equalTo("application/octet-stream"));

        try (InputStream in = target.get(fileId)) {
            assertArrayEquals(content, ByteStreams.toByteArray(in), "bytes must round-trip exactly");
        }
    }

    /** Three parts (5 MB + 5 MB + 1 byte): part ordering and a 1-byte final part on a real bucket. */
    @Test
    public void putAndGetThreePartFileViaMultipart() throws IOException {
        final int size = 2 * MULTIPART_THRESHOLD + 1;
        final byte[] content = patterned(size);
        final String fileId = put(content, "three-parts.bin", "application/octet-stream");

        assertThat(target.getSize(fileId), equalTo((long) size));
        try (InputStream in = target.get(fileId)) {
            assertArrayEquals(content, ByteStreams.toByteArray(in), "parts must be assembled in order");
        }
    }


    /** Exactly the threshold stays on the single-PUT path (so `size` metadata is written). */
    @Test
    public void exactlyThresholdSizedFileRoundTrips() throws IOException {
        final byte[] content = patterned(MULTIPART_THRESHOLD);
        final String fileId = put(content, "exact.bin", "application/octet-stream");

        assertThat(target.getSize(fileId), equalTo((long) MULTIPART_THRESHOLD));
        try (InputStream in = target.get(fileId)) {
            assertArrayEquals(content, ByteStreams.toByteArray(in));
        }
    }

    @Test
    public void putAndGetEmptyFile() throws IOException {
        final String fileId = put(new byte[0], "empty.txt", TEXT_PLAIN);

        assertTrue(target.exists(fileId), "empty file must exist");
        assertThat(target.getSize(fileId), equalTo(0L));
        try (InputStream in = target.get(fileId)) {
            assertThat(ByteStreams.toByteArray(in).length, equalTo(0));
        }
    }

    /** Non-uniform bytes, so truncation or reordering changes the content rather than just the length. */
    private static byte[] patterned(final int size) {
        final byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    // ── exists() ---------------------------------------------------------

    @Test
    public void existsReturnsFalseForUnknownId() {
        assertFalse(target.exists("0123456789abcdef0123456789abcdef"), "unknown id must not exist");
    }

    // ── access URL and stripped ids --------------------------------------

    @Test
    public void getAccessUrlHasProtocolPrefixFormat() throws IOException {
        final String fileId = put(CONTENT.getBytes(StandardCharsets.UTF_8), FILENAME, TEXT_PLAIN);

        assertThat(target.getAccessUrl(fileId).toString(),
                equalTo(fixture.getProtocol().toLowerCase() + ":" + fileId + "-" + FILENAME));
    }

    @Test
    public void existsAcceptsProtocolPrefixedId() throws IOException {
        final String fileId = put(CONTENT.getBytes(StandardCharsets.UTF_8), FILENAME, TEXT_PLAIN);

        assertTrue(target.exists(fixture.getProtocol().toLowerCase() + ":" + fileId),
                "getStrippedId must remove the protocol prefix");
    }

    // ── file name sanitisation -------------------------------------------

    @Test
    public void fileNameIsSanitised() throws IOException {
        final String fileId = put(CONTENT.getBytes(StandardCharsets.UTF_8), "my$file (copy)[1].txt", TEXT_PLAIN);

        // FilenameUtils.makeValidFilename strips $()+=[];#@~,&' and collapses whitespace,
        // but deliberately preserves single spaces.
        assertThat(target.getFileName(fileId), equalTo("myfile copy1.txt"));
    }

    // ── mime type resolution via MimeTypeService -------------------------

    @Test
    public void mimeTypeResolvedWhenNotSupplied() throws IOException {
        when(mimeTypeServiceMock.getMimeType(FILENAME)).thenReturn(TEXT_PLAIN);

        final String fileId = put(CONTENT.getBytes(StandardCharsets.UTF_8), FILENAME, null);

        assertThat(target.getMimeType(fileId), equalTo(TEXT_PLAIN));
        assertThat(target.getAccessUrl(fileId).toString(), startsWith(fixture.getProtocol().toLowerCase() + ":"));
    }
}
