package hu.blackbelt.osgi.filestore.servlet.utils;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

import static hu.blackbelt.osgi.filestore.servlet.Constants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CorsProcessorTest {

    private static final List<String> UPLOAD_METHODS = Arrays.asList(METHOD_GET, METHOD_POST, METHOD_OPTIONS);
    private static final List<String> DOWNLOAD_METHODS = Arrays.asList(METHOD_GET, METHOD_OPTIONS);

    private HttpServletRequest request;
    private HttpServletResponse response;
    private Map<String, List<String>> responseHeaders;
    private int[] status;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        responseHeaders = new LinkedHashMap<>();
        status = new int[] {200};

        when(request.getHeaders(anyString())).thenReturn(Collections.enumeration(Collections.emptyList()));
        doAnswer(i -> responseHeaders.computeIfAbsent(i.getArgument(0), k -> new ArrayList<>()).add(i.getArgument(1)))
                .when(response).addHeader(anyString(), anyString());
        doAnswer(i -> responseHeaders.put(i.getArgument(0), new ArrayList<>(Collections.singletonList(i.getArgument(1)))))
                .when(response).setHeader(anyString(), anyString());
        doAnswer(i -> status[0] = i.getArgument(0)).when(response).setStatus(anyInt());
    }

    private void header(String name, String... values) {
        when(request.getHeaders(name)).thenReturn(Collections.enumeration(Arrays.asList(values)));
    }

    private String header(String name) {
        final List<String> values = responseHeaders.get(name);
        return values == null ? null : String.join(",", values);
    }

    @Test
    void testSuccessfulPreflightWithExplicitOrigin() {
        when(request.getMethod()).thenReturn(METHOD_OPTIONS);
        header(HEADER_ORIGIN, "https://example.com");
        header(HEADER_ACCESS_CONTROL_REQUEST_METHOD, METHOD_POST);
        header(HEADER_ACCESS_CONTROL_REQUEST_HEADERS, HEADER_CONTENT_TYPE + "," + HEADER_TOKEN);

        final boolean proceed = CorsProcessor.builder()
                .allowOrigins(Collections.singleton("https://example.com"))
                .allowCredentials(true)
                .build()
                .process(request, response, UPLOAD_METHODS);

        assertThat(proceed, is(false));
        assertThat(status[0], is(200));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN), is("https://example.com"));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_METHODS), is(METHOD_POST));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_HEADERS), containsString(HEADER_TOKEN));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS), is("true"));
    }

    @Test
    void testPreflightRejectedForDisallowedOrigin() {
        when(request.getMethod()).thenReturn(METHOD_OPTIONS);
        header(HEADER_ORIGIN, "https://evil.com");
        header(HEADER_ACCESS_CONTROL_REQUEST_METHOD, METHOD_POST);

        final boolean proceed = CorsProcessor.builder()
                .allowOrigins(Collections.singleton("https://trusted.com"))
                .build()
                .process(request, response, UPLOAD_METHODS);

        assertThat(proceed, is(false));
        assertThat(status[0], is(CORS_PREFLIGHT_ERROR_CODE));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN), nullValue());
    }

    @Test
    void testWildcardRequestDoesNotEchoOriginNorGrantCredentials() {
        when(request.getMethod()).thenReturn(METHOD_GET);
        header(HEADER_ORIGIN, "https://any-origin.com");

        final boolean proceed = CorsProcessor.builder()
                .allowOrigins(Collections.singleton(ALL))
                .allowCredentials(true)
                .exposeHeaders(Arrays.asList(HEADER_CONTENT_TYPE, HEADER_CONTENT_DISPOSITION))
                .build()
                .process(request, response, DOWNLOAD_METHODS);

        assertThat(proceed, is(true));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN), is(ALL));
        assertThat(header(HEADER_ACCESS_CONTROL_EXPOSE_HEADERS),
                is(HEADER_CONTENT_TYPE + "," + HEADER_CONTENT_DISPOSITION));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS), nullValue());
    }

    @Test
    void testWildcardPreflightDoesNotGrantCredentials() {
        when(request.getMethod()).thenReturn(METHOD_OPTIONS);
        header(HEADER_ORIGIN, "https://evil.com");
        header(HEADER_ACCESS_CONTROL_REQUEST_METHOD, METHOD_POST);

        final boolean proceed = CorsProcessor.builder()
                .allowOrigins(Collections.singleton(ALL))
                .allowCredentials(true)
                .build()
                .process(request, response, UPLOAD_METHODS);

        assertThat(proceed, is(false));
        assertThat(status[0], is(200));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN), is(ALL));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS), nullValue());
    }

    @Test
    void testExplicitOriginListKeepsCredentials() {
        when(request.getMethod()).thenReturn(METHOD_GET);
        header(HEADER_ORIGIN, "https://app.example.com");

        final boolean proceed = CorsProcessor.builder()
                .allowOrigins(Collections.singleton("https://app.example.com"))
                .allowCredentials(true)
                .build()
                .process(request, response, DOWNLOAD_METHODS);

        assertThat(proceed, is(true));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN), is("https://app.example.com"));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS), is("true"));
    }

    @Test
    void testRequestWithoutOriginHeader() {
        when(request.getMethod()).thenReturn(METHOD_GET);

        final boolean proceed = CorsProcessor.builder()
                .allowOrigins(Collections.singleton(ALL))
                .build()
                .process(request, response, DOWNLOAD_METHODS);

        assertThat(proceed, is(true));
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN), nullValue());
        assertThat(header(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS), nullValue());
        assertThat(header(HEADER_ACCESS_CONTROL_EXPOSE_HEADERS), nullValue());
    }

    @Test
    void testDisallowedMethod() {
        when(request.getMethod()).thenReturn(METHOD_POST);

        final boolean proceed = CorsProcessor.builder()
                .allowOrigins(Collections.singleton(ALL))
                .build()
                .process(request, response, DOWNLOAD_METHODS);

        assertThat(proceed, is(false));
        assertThat(status[0], is(HttpServletResponse.SC_METHOD_NOT_ALLOWED));
    }
}
