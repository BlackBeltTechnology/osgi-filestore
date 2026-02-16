package hu.blackbelt.osgi.filestore.s3;

import com.google.common.io.ByteStreams;
import hu.blackbelt.osgi.filestore.s3.fixture.MinioFixture;
import hu.blackbelt.osgi.filestore.s3.fixture.MinioSingletonExtension;
import org.apache.sling.commons.mime.MimeTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(MinioSingletonExtension.class)
public class S3FileStoreServiceTest {

    private static final String FILENAME = "test.txt";
    private static final String TEXT_PLAIN = "text/plain";

    @InjectMocks
    S3FileStoreService target;

    @Mock
    MimeTypeService mimeTypeServiceMock;

    @Mock
    BundleContext context;

    MinioFixture minioFixture;

    S3FileStoreServiceTest(MinioFixture minioFixture) {
        this.minioFixture = minioFixture;
    }

    @BeforeEach
    public void setup() {
        target.s3Client = minioFixture.getS3Client();
        target.bucketName = minioFixture.getBucketName();

        S3FileStoreService.Config config = mock(S3FileStoreService.Config.class);
        when(config.protocol()).thenReturn("s3store");
        when(config.bucketName()).thenReturn(minioFixture.getBucketName());

        target.activate(null, config);
    }

    @Test
    public void testPutAndGetWithNullMimeType() throws IOException {
        when(mimeTypeServiceMock.getMimeType(endsWith(".txt"))).thenReturn("text/plain");

        String fileId = target.put(this.getClass().getClassLoader().getResourceAsStream("test.txt"), "test.txt", null);
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getFileName(fileId), equalTo("test.txt"));
        assertThat(target.getMimeType(fileId), equalTo("text/plain"));
        assertThat(new String(ByteStreams.toByteArray(target.get(fileId))), equalTo("test"));
    }

    @Test
    public void testPutAndGetWithNullMimeTypeAndFileName() throws IOException {
        when(mimeTypeServiceMock.getMimeType(endsWith(".bin"))).thenReturn("application/octetstream");

        String fileId = target.put(this.getClass().getClassLoader().getResourceAsStream("test.txt"), null, null);
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getFileName(fileId), equalTo(fileId + ".bin"));
        assertThat(target.getMimeType(fileId), equalTo("application/octetstream"));
        assertThat(new String(ByteStreams.toByteArray(target.get(fileId))), equalTo("test"));
    }

    @Test
    public void testPutAndGetWithMimeTypeAndNullFileName() throws IOException {
        when(mimeTypeServiceMock.getExtension("text/plain")).thenReturn("txt");

        String fileId = target.put(this.getClass().getClassLoader().getResourceAsStream("test.txt"), null, "text/plain");
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getFileName(fileId), equalTo(fileId + ".txt"));
        assertThat(target.getMimeType(fileId), equalTo("text/plain"));
        assertThat(new String(ByteStreams.toByteArray(target.get(fileId))), equalTo("test"));
    }

    @Test
    public void testGetIllegalFileId() {
        assertThat(target.exists("notexists"), equalTo(false));
        assertThrows(IllegalArgumentException.class, () -> {
            target.getFileName("notexists");
        });
    }

    @Test
    public void testGetNullFileId() {
        assertThrows(IllegalArgumentException.class, () -> {
            target.exists(null);
        });
    }

    @Test
    public void testPutAndGetLargeFile() throws IOException {
        when(mimeTypeServiceMock.getMimeType(endsWith(".bin"))).thenReturn("application/octet-stream");

        byte[] largeContent = new byte[5 * 1024 * 1024]; // 5 MB
        new java.util.Random(42).nextBytes(largeContent);
        InputStream largeStream = new ByteArrayInputStream(largeContent);

        String fileId = target.put(largeStream, null, null);
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getSize(fileId), equalTo((long) largeContent.length));

        byte[] retrieved = ByteStreams.toByteArray(target.get(fileId));
        assertArrayEquals(largeContent, retrieved);
    }

    @Test
    public void testPutAndGetSmallFileSize() throws IOException {
        byte[] content = "hello world".getBytes();
        String fileId = target.put(new ByteArrayInputStream(content), "small.txt", "text/plain");
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getSize(fileId), equalTo((long) content.length));
    }

    @Test
    public void testPutAndGetLargeFileMultipart() throws IOException {
        when(mimeTypeServiceMock.getMimeType(endsWith(".bin"))).thenReturn("application/octet-stream");

        byte[] largeContent = new byte[6 * 1024 * 1024]; // 6 MB — exceeds 5MB threshold
        new java.util.Random(99).nextBytes(largeContent);
        InputStream largeStream = new ByteArrayInputStream(largeContent);

        String fileId = target.put(largeStream, null, null);
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getSize(fileId), equalTo((long) largeContent.length));

        byte[] retrieved = ByteStreams.toByteArray(target.get(fileId));
        assertArrayEquals(largeContent, retrieved);
    }

    @Test
    public void testPutAndGetEmptyFile() throws IOException {
        byte[] content = new byte[0];
        String fileId = target.put(new ByteArrayInputStream(content), "empty.txt", "text/plain");
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getSize(fileId), equalTo(0L));
    }

    @Test
    public void testS3ClientConnectivity() {
        assertNotNull(minioFixture.getS3Client());
        assertNotNull(minioFixture.getEndpoint());
        assertThat(minioFixture.getEndpoint().length(), greaterThan(0));
    }
}
