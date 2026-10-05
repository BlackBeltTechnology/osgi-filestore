package hu.blackbelt.osgi.filestore.servlet;

/*-
 * #%L
 * Filestore servlet (file upload)
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.http.HttpService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static hu.blackbelt.osgi.filestore.servlet.TestSupport.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;

class ServletConfigurationWarningTest {

    private LogCapture logs;
    private HttpService httpService;

    @BeforeEach
    void setUp() {
        logs = new LogCapture();
        httpService = mock(HttpService.class);
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    private static Map<String, Object> cfg(Object... keysAndValues) {
        final Map<String, Object> values = new HashMap<>();
        values.put("servletPath", "/test/upload");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return values;
    }

    private List<String> activateUpload(Map<String, Object> values) throws Exception {
        final UploadServlet servlet = new UploadServlet();
        inject(servlet, "httpService", httpService);
        activate(servlet, config(UploadServlet.Config.class, values));
        return logs.warnings();
    }

    private List<String> activateDownload(Map<String, Object> values) throws Exception {
        final DownloadServlet servlet = new DownloadServlet();
        inject(servlet, "httpService", httpService);
        activate(servlet, config(DownloadServlet.Config.class, values));
        return logs.warnings();
    }

    @Test
    void testUploadServletWarnsWhenTokenNotRequired() throws Exception {
        final List<String> warnings = activateUpload(cfg("tokenRequired", false, "cors_allowOrigin", "https://app.example.com"));

        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), allOf(containsString("tokenRequired"), containsString("/test/upload")));
    }

    @Test
    void testDownloadServletWarnsWhenTokenNotRequired() throws Exception {
        final List<String> warnings = activateDownload(cfg("tokenRequired", false, "cors_allowOrigin", "https://app.example.com"));

        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), allOf(containsString("tokenRequired"), containsString("/test/upload")));
    }

    @Test
    void testUploadServletWarnsOnWildcardOriginWithCredentials() throws Exception {
        final List<String> warnings = activateUpload(cfg("tokenRequired", true, "cors_allowOrigin", "*", "cors_allowCredentials", true));

        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), allOf(containsString("cors.allowOrigin"), containsString("cors.allowCredentials")));
    }

    @Test
    void testDownloadServletWarnsOnWildcardOriginWithCredentials() throws Exception {
        final List<String> warnings = activateDownload(cfg("tokenRequired", true, "cors_allowOrigin", "*", "cors_allowCredentials", true));

        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), allOf(containsString("cors.allowOrigin"), containsString("cors.allowCredentials")));
    }

    @Test
    void testUploadServletIsSilentOnSafeConfiguration() throws Exception {
        assertThat(activateUpload(cfg("tokenRequired", true, "cors_allowOrigin", "https://app.example.com")), is(empty()));
    }

    @Test
    void testDownloadServletIsSilentOnSafeConfiguration() throws Exception {
        assertThat(activateDownload(cfg("tokenRequired", true, "cors_allowOrigin", "https://app.example.com")), is(empty()));
    }

    @Test
    void testWildcardOriginWithoutCredentialsIsSilent() throws Exception {
        assertThat(activateUpload(cfg("tokenRequired", true, "cors_allowOrigin", "*", "cors_allowCredentials", false)), is(empty()));
    }
}
