package hu.blackbelt.osgi.filestore.filesystem;

import com.google.common.io.ByteStreams;
import hu.blackbelt.osgi.utils.test.MockOsgi;
import org.apache.sling.commons.mime.MimeTypeService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.osgi.framework.BundleContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.endsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class FileSystemFileStoreServiceTest {

    @InjectMocks
    FileSystemFileStoreService target;

    @Mock
    MimeTypeService mimeTypeServiceMock;

    @Mock
    BundleContext context;

    private Path root;

    @Before
    public void setup() throws IOException {
        root = Files.createTempDirectory("tmp-filestore");

        FileSystemFileStoreService.Config config = mock(FileSystemFileStoreService.Config.class);
        when(config.fileSystemStoreDirectory()).thenReturn(root.toString());
        when(config.protocol()).thenReturn("judostore");

        target.activate(context, config);

        MockOsgi.setReferences(target, mimeTypeServiceMock);
    }

    @Test
    public void testPutAndGetWithNullMimeType() throws IOException {
        when(mimeTypeServiceMock.getExtension("text/plain")).thenReturn("txt");
        when(mimeTypeServiceMock.getMimeType(endsWith(".txt"))).thenReturn("text/plain");

        String fileId = target.put(this.getClass().getClassLoader().getResourceAsStream("test.txt"), "test.txt", null);
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getFileName(fileId), equalTo("test.txt"));
        assertThat(target.getMimeType(fileId), equalTo("text/plain"));
        assertThat(new String(ByteStreams.toByteArray(target.get(fileId))), equalTo("test"));
    }

    @Test
    public void testPutAndGetWithNullMimeTypeAndFileName() throws IOException {
        when(mimeTypeServiceMock.getExtension("application/octetstream")).thenReturn("bin");
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
        when(mimeTypeServiceMock.getMimeType(endsWith(".txt"))).thenReturn("text/plain");

        String fileId = target.put(this.getClass().getClassLoader().getResourceAsStream("test.txt"), null, "text/plain");
        assertThat(target.exists(fileId), equalTo(true));
        assertThat(target.getFileName(fileId), equalTo(fileId + ".txt"));
        assertThat(target.getMimeType(fileId), equalTo("text/plain"));
        assertThat(new String(ByteStreams.toByteArray(target.get(fileId))), equalTo("test"));
    }

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testGetIllegalFileId() throws IOException {
        assertThat(target.exists("notexists"), equalTo(false));
        thrown.expect(IOException.class);
        target.getFileName("notexists");
        target.getMimeType("notexists");
        target.get("notexists");
    }

    @Test
    public void testGetNullFileId() throws IOException {
        thrown.expect(NullPointerException.class);
        target.exists(null);
        target.getFileName(null);
        target.getMimeType(null);
        target.get(null);
    }

}
