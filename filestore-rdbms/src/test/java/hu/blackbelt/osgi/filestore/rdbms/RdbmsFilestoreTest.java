package hu.blackbelt.osgi.filestore.rdbms;

/*-
 * #%L
 * JUDO framework RDBMS filestore
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

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
import static org.mockito.ArgumentMatchers.endsWith;
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
                ImmutableMap.of("table-name", "FILESTORE_CamelCase".toUpperCase()));

        RdbmsFileStoreService.Config config = mock(RdbmsFileStoreService.Config.class);
        when(config.protocol()).thenReturn("judostore");
        when(config.table()).thenReturn("FILESTORE_CamelCase".toUpperCase());

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
