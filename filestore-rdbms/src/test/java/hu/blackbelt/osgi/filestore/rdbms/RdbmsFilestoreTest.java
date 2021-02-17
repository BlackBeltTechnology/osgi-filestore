package hu.blackbelt.osgi.filestore.rdbms;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import hu.blackbelt.osgi.filestore.rdbms.fixture.RdbmsDatasourceFixture;
import hu.blackbelt.osgi.filestore.rdbms.fixture.RdbmsDatasourceSingetonExtension;
import org.apache.sling.commons.mime.MimeTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;

import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Matchers.endsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(RdbmsDatasourceSingetonExtension.class)
public class RdbmsFilestoreTest {

    private InputStream data;
    private static final String FILENAME = "test.txt";
    private static final String TEXT_PLAIN = "text/plain";

    @InjectMocks
    RdbmsFileStoreService target;

    @Mock
    MimeTypeService mimeTypeServiceMock;

    @Mock
    BundleContext context;

    RdbmsDatasourceFixture rdbmsDatasourceFixture;

    RdbmsFilestoreTest(RdbmsDatasourceFixture rdbmsDatasourceFixture) {
        this.rdbmsDatasourceFixture = rdbmsDatasourceFixture;
    }

    @BeforeEach
    public void setup() {
        target.dataSource = rdbmsDatasourceFixture.getDataSource();
        rdbmsDatasourceFixture.executeInitiLiquibase(
                RdbmsFileStoreService.class.getClassLoader(), "liquibase/changelog.xml", rdbmsDatasourceFixture.getDataSource(),
                ImmutableMap.of("table-name", "FILESTORE"));

        RdbmsFileStoreService.Config config = mock(RdbmsFileStoreService.Config.class);
        when(config.protocol()).thenReturn("judostore");
        when(config.table()).thenReturn("FILESTORE");

        target.activate(context, config);
        data = this.getClass().getClassLoader().getResourceAsStream("test.txt");
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
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            target.getFileName("notexists");
            target.getMimeType("notexists");
            target.get("notexists");
        });
    }

    @Test
    public void testGetNullFileId() {
        assertThat(target.exists("notexists"), equalTo(false));
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            target.exists(null);
            target.getFileName(null);
            target.getMimeType(null);
            target.get(null);
        });
    }

}
