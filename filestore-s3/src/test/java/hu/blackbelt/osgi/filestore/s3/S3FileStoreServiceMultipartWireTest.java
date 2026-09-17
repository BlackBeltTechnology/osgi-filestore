package hu.blackbelt.osgi.filestore.s3;

import com.github.davidmoten.aws.lw.client.Client;
import com.github.davidmoten.aws.lw.client.HttpClient;
import com.github.davidmoten.aws.lw.client.ResponseInputStream;
import com.github.davidmoten.aws.lw.client.xml.XmlElement;
import org.apache.sling.commons.mime.MimeTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the exact requests {@link S3FileStoreService#put} hands to the HTTP transport. No network, no
 * container: the client library's public {@link HttpClient} seam is replaced by a recorder.
 *
 * <p>The two integration suites (MinIO, GCS) prove the outcome on real backends. This test proves the
 * mechanism, so a regression is caught by name rather than as an opaque 400/411 from a bucket. The two
 * tests named {@code fixed*} guard the defects that once made files ≥ 5 MB unstorable on Google Cloud
 * Storage — see GCS_INTEROP.md §6.1. What is pinned:
 * <ul>
 *   <li>the multipart initiate carries an <em>empty</em> body, never an <em>absent</em> one — that is
 *       what makes the JDK emit {@code Content-Length: 0}, which Google Cloud Storage requires;</li>
 *   <li>the complete document declares no malformed namespace (GCS rejects
 *       {@code http:s3.amazonaws.com/…}, which is missing {@code //});</li>
 *   <li>parts are numbered from 1, ascending, each exactly {@code MULTIPART_THRESHOLD} bytes except the
 *       last, and their concatenation is the original payload;</li>
 *   <li>a failed part stops the upload before the complete request is sent;</li>
 *   <li>payloads up to and including the threshold never enter the multipart path.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class S3FileStoreServiceMultipartWireTest {

    private static final int MB = 1024 * 1024;
    private static final int THRESHOLD = 5 * MB;
    private static final String BUCKET = "wire-bucket";
    private static final String UPLOAD_ID = "wire-upload-id";
    private static final Pattern PART_NUMBER = Pattern.compile("(?:^|&)partNumber=(\\d+)");

    /** One request as seen by the transport. Body is copied because production reuses its buffer. */
    static final class Recorded {
        final String method;
        final URL url;
        final Map<String, String> headers;
        final byte[] body;

        Recorded(String method, URL url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body == null ? null : Arrays.copyOf(body, body.length);
        }

        boolean isInitiate() {
            return "POST".equals(method) && query().contains("uploads");
        }

        boolean isPart() {
            return "PUT".equals(method) && PART_NUMBER.matcher(query()).find();
        }

        boolean isComplete() {
            return "POST".equals(method) && query().contains("uploadId=") && !query().contains("uploads");
        }

        boolean isSinglePut() {
            return "PUT".equals(method) && query().isEmpty();
        }

        int partNumber() {
            Matcher m = PART_NUMBER.matcher(query());
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }

        String query() {
            return url.getQuery() == null ? "" : url.getQuery();
        }
    }

    /** Records every request and answers like a well-behaved S3 endpoint. */
    static final class RecordingHttpClient implements HttpClient {
        final List<Recorded> requests = new ArrayList<>();
        int failPartNumber = -1;

        @Override
        public ResponseInputStream request(URL url, String method, Map<String, String> headers,
                                           byte[] body, int connectTimeoutMs, int readTimeoutMs) {
            Recorded r = new Recorded(method, url, headers, body);
            requests.add(r);
            if (r.isInitiate()) {
                return ok("<InitiateMultipartUploadResult><UploadId>" + UPLOAD_ID + "</UploadId>"
                        + "</InitiateMultipartUploadResult>", Collections.emptyMap());
            }
            if (r.isPart()) {
                if (r.partNumber() == failPartNumber) {
                    return response(403, "<Error><Code>AccessDenied</Code></Error>", Collections.emptyMap());
                }
                return ok("", Map.of("ETag", List.of("\"etag-" + r.partNumber() + "\"")));
            }
            if (r.isComplete()) {
                return ok("<CompleteMultipartUploadResult/>", Collections.emptyMap());
            }
            return ok("", Collections.emptyMap());
        }

        private static ResponseInputStream ok(String body, Map<String, List<String>> headers) {
            return response(200, body, headers);
        }

        private static ResponseInputStream response(int status, String body, Map<String, List<String>> headers) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            // The library only reads a response body when Content-Length (or chunked encoding) is
            // announced, exactly like a real server would.
            Map<String, List<String>> h = new java.util.HashMap<>(headers);
            h.put("Content-Length", List.of(String.valueOf(bytes.length)));
            return new ResponseInputStream(() -> { }, status, h, new ByteArrayInputStream(bytes));
        }
    }

    /** Hands out at most {@code chunk} bytes per read, to exercise short reads. */
    static final class TricklingInputStream extends InputStream {
        private final InputStream delegate;
        private final int chunk;

        TricklingInputStream(byte[] data, int chunk) {
            this.delegate = new ByteArrayInputStream(data);
            this.chunk = chunk;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, Math.min(len, chunk));
        }
    }

    @InjectMocks
    S3FileStoreService target;

    @Mock
    MimeTypeService mimeTypeService;

    private RecordingHttpClient http;

    @BeforeEach
    void setup() {
        http = new RecordingHttpClient();
        target.s3Client = Client.s3().region("us-east-1")
                .accessKey("wire-access").secretKey("wire-secret")
                .baseUrlFactory((service, region) -> "http://wire.invalid/")
                .httpClient(http)
                .retryMaxAttempts(1)
                .build();
        target.bucketName = BUCKET;

        S3FileStoreService.Config config = mock(S3FileStoreService.Config.class);
        when(config.protocol()).thenReturn("wirestore");
        when(config.bucketName()).thenReturn(BUCKET);
        target.activate(null, config);
    }

    private static byte[] payload(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i % 251); // non-uniform, so any reordering or truncation changes the bytes
        }
        return b;
    }

    private void put(InputStream data) throws IOException {
        target.put(data, "wire.bin", "application/octet-stream");
    }

    /** Part requests ordered by part number - arrival order is an implementation detail. */
    private List<Recorded> parts() {
        return http.requests.stream().filter(Recorded::isPart)
                .sorted(java.util.Comparator.comparingInt(Recorded::partNumber))
                .collect(Collectors.toList());
    }

    private Recorded only(java.util.function.Predicate<Recorded> which, String what) {
        List<Recorded> hits = http.requests.stream().filter(which).collect(Collectors.toList());
        assertThat("exactly one " + what + " request", hits, hasSize(1));
        return hits.get(0);
    }

    // ── initiate ─────────────────────────────────────────────────────────

    /**
     * REGRESSION GUARD for the first of the two defects that broke GCS.
     *
     * <p>The initiate request legitimately has an empty body. The library's own {@code Multipart}
     * helper passed <em>no</em> body, so {@code HttpClientDefault} never opened the output stream and
     * {@code HttpURLConnection} emitted no {@code Content-Length} — GCS answers {@code 411 Length
     * Required}. A body of {@code new byte[0]} — empty, not absent — is what produces
     * {@code Content-Length: 0}. Amazon S3 and MinIO tolerate both, so only GCS catches a regression.
     */
    @Test
    void fixedInitiateSendsEmptyBodyNotAbsentBody() throws IOException {
        put(new ByteArrayInputStream(payload(6 * MB)));

        Recorded initiate = only(Recorded::isInitiate, "initiate");
        assertNotNull(initiate.body,
                "initiate body must be present (empty), not null - an absent body omits "
                        + "Content-Length and GCS answers 411; see GCS_INTEROP.md §6.1");
        assertThat(initiate.body.length, equalTo(0));
        assertThat(initiate.headers.get("Content-Type"), equalTo("application/octet-stream"));
        assertThat(initiate.headers.get("x-amz-meta-filename"), equalTo("wire.bin"));
        assertThat(initiate.headers.get("x-amz-meta-mimetype"), equalTo("application/octet-stream"));
        assertNotNull(initiate.headers.get("x-amz-meta-createtime"));
    }

    // ── parts ────────────────────────────────────────────────────────────

    @Test
    void partsAreAscendingThresholdSizedAndReassembleToThePayload() throws IOException {
        byte[] data = payload(10 * MB + 1); // 5 MB + 5 MB + 1 byte -> three parts
        put(new ByteArrayInputStream(data));

        List<Recorded> parts = parts();
        assertThat(parts.stream().map(Recorded::partNumber).collect(Collectors.toList()), contains(1, 2, 3));
        assertThat(parts.get(0).body.length, equalTo(THRESHOLD));
        assertThat(parts.get(1).body.length, equalTo(THRESHOLD));
        assertThat(parts.get(2).body.length, equalTo(1));
        parts.forEach(p -> assertThat(p.query(), containsString("uploadId=" + UPLOAD_ID)));

        ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
        for (Recorded p : parts) {
            reassembled.write(p.body, 0, p.body.length);
        }
        assertArrayEquals(data, reassembled.toByteArray(), "concatenated parts must equal the payload");
    }

    @Test
    void shortReadsStillProduceFullSizedParts() throws IOException {
        byte[] data = payload(6 * MB);
        put(new TricklingInputStream(data, 1000)); // never more than 1000 bytes per read()

        List<Recorded> parts = parts();
        assertThat(parts, hasSize(2));
        assertThat("first part must be filled to the threshold despite short reads",
                parts.get(0).body.length, equalTo(THRESHOLD));
        assertThat(parts.get(1).body.length, equalTo(MB));

        ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
        for (Recorded p : parts) {
            reassembled.write(p.body, 0, p.body.length);
        }
        assertArrayEquals(data, reassembled.toByteArray());
    }

    // ── complete ─────────────────────────────────────────────────────────

    /**
     * REGRESSION GUARD for the second of the two defects that broke GCS.
     *
     * <p>The library's {@code MultipartOutputStream} declares
     * {@code xmlns="http:s3.amazonaws.com/doc/2006-03-01/"} — note the missing {@code //}, which is not
     * a valid absolute URI. Amazon S3 and MinIO ignore it; GCS schema-validates and answers
     * {@code 400 MalformedCompleteMultipartUploadRequest}. Either no namespace at all or the
     * well-formed {@code http://s3.amazonaws.com/doc/2006-03-01/} is accepted by all three backends.
     */
    @Test
    void fixedCompleteDocumentIsWellFormedOrderedAndCarriesNoMalformedNamespace() throws Exception {
        put(new ByteArrayInputStream(payload(10 * MB + 1)));

        Recorded complete = only(Recorded::isComplete, "complete");
        String xml = new String(complete.body, StandardCharsets.UTF_8);

        XmlElement root = XmlElement.parse(xml);
        assertThat(root.name(), equalTo("CompleteMultipartUpload"));

        // No namespace is required; a malformed one is fatal on GCS.
        String xmlns = root.attribute("xmlns");
        if (xmlns != null) {
            assertThat(xmlns, startsWith("http://"));
        }
        assertThat(xml, not(containsString("http:s3.amazonaws.com")));

        List<XmlElement> partElements = root.childrenWithName("Part");
        assertThat(partElements, hasSize(3));
        for (int i = 0; i < partElements.size(); i++) {
            XmlElement part = partElements.get(i);
            assertThat(part.content("PartNumber"), equalTo(String.valueOf(i + 1)));
            assertThat("ETag must be echoed verbatim, quotes included",
                    part.content("ETag"), equalTo("\"etag-" + (i + 1) + "\""));
        }
    }

    @Test
    void completeIsSentAfterAllPartsAndAddressesTheSameUpload() throws IOException {
        put(new ByteArrayInputStream(payload(6 * MB)));

        List<Recorded> all = http.requests;
        int initiateAt = indexOf(Recorded::isInitiate);
        int lastPartAt = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).isPart()) {
                lastPartAt = i;
            }
        }
        int completeAt = indexOf(Recorded::isComplete);

        assertThat(initiateAt, is(0));
        assertThat("complete must come after the last part", completeAt, equalTo(all.size() - 1));
        assertThat(lastPartAt, is(completeAt - 1));
        assertThat(all.get(completeAt).query(), containsString("uploadId=" + UPLOAD_ID));
    }

    // ── failure ──────────────────────────────────────────────────────────

    @Test
    void failedPartPropagatesAndNoCompleteIsSent() {
        http.failPartNumber = 2;

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> put(new ByteArrayInputStream(payload(6 * MB))));

        assertThat(thrown.getMessage(), containsString("403"));
        assertThat("a complete request after a failed part would assemble a truncated object",
                http.requests.stream().filter(Recorded::isComplete).collect(Collectors.toList()), empty());
    }

    // ── threshold ────────────────────────────────────────────────────────

    @Test
    void belowThresholdIsASinglePutWithSizeMetadata() throws IOException {
        byte[] data = payload(4 * MB);
        put(new ByteArrayInputStream(data));

        Recorded single = only(Recorded::isSinglePut, "single PUT");
        assertThat(http.requests, hasSize(1));
        assertArrayEquals(data, single.body);
        assertThat(single.headers.get("x-amz-meta-size"), equalTo(String.valueOf(4 * MB)));
    }

    @Test
    void exactlyThresholdIsStillASinglePut() throws IOException {
        byte[] data = payload(THRESHOLD);
        put(new ByteArrayInputStream(data));

        Recorded single = only(Recorded::isSinglePut, "single PUT");
        assertThat("exactly 5 MB must not open a multipart upload", http.requests, hasSize(1));
        assertArrayEquals(data, single.body);
        assertThat(single.headers.get("x-amz-meta-size"), equalTo(String.valueOf(THRESHOLD)));
    }

    @Test
    void oneByteOverThresholdOpensMultipartWithTwoParts() throws IOException {
        put(new ByteArrayInputStream(payload(THRESHOLD + 1)));

        only(Recorded::isInitiate, "initiate");
        List<Recorded> parts = parts();
        assertThat(parts, hasSize(2));
        assertThat(parts.get(0).body.length, equalTo(THRESHOLD));
        assertThat(parts.get(1).body.length, equalTo(1));
        only(Recorded::isComplete, "complete");
    }

    private int indexOf(java.util.function.Predicate<Recorded> which) {
        for (int i = 0; i < http.requests.size(); i++) {
            if (which.test(http.requests.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
