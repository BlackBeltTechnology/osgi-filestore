package hu.blackbelt.osgi.filestore.itest;

/*-
 * #%L
 * Integeration test for JUDO framework RDBMS filestore
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

import hu.blackbelt.osgi.filestore.security.api.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jose4j.json.JsonUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.options.MavenArtifactUrlReference;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;
import org.ops4j.pax.exam.util.Filter;

import javax.inject.Inject;
import javax.servlet.Servlet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static hu.blackbelt.osgi.filestore.itest.utils.KarafFeatureProvider.karafConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.text.StringContainsInOrder.stringContainsInOrder;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.OptionUtils.combine;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.newConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFilePut;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
@Slf4j
public class FilestoreTest {

    public static final String FEATURE_FILESTORE_FULL = "filestore-full";

    private static final String TEST_ORIGIN = "http://www.example.com";
    private static final String TEST_METHOD = "GET";
    private static final Collection<String> TEST_HEADERS = Arrays.asList("Origin", "Accept", "Content-Type");

    @Inject
    TokenIssuer tokenIssuer;

    @Inject
    TokenValidator tokenValidator;

    @Inject
    @Filter("(component.name=hu.blackbelt.osgi.filestore.servlet.UploadServlet)")
    Servlet uploadServlet;

    @Inject
    @Filter("(component.name=hu.blackbelt.osgi.filestore.servlet.DownloadServlet)")
    Servlet downloadServlet;

    public static MavenArtifactUrlReference filestoreArtifact() {
        return maven().groupId("hu.blackbelt.osgi.filestore").artifactId("features").versionAsInProject().classifier("features").type("xml");
    }

    @Configuration
    @SneakyThrows
    public Option[] config() {
        final String issuer = "Issuer";

        return combine(karafConfig(this.getClass()),

                systemProperty("org.ops4j.pax.exam.raw.extender.intern.Parser.DEFAULT_TIMEOUT").value("120000"),
                systemProperty("pax.exam.service.timeout").value("120000"),

                features(filestoreArtifact(), FEATURE_FILESTORE_FULL),

                editConfigurationFilePut("etc/org.ops4j.pax.web.cfg", "org.osgi.service.http.port", "8181"),

                newConfiguration("hu.blackbelt.osgi.filestore.filesystem.FileSystemFileStoreService")
                        .put("protocol", "judostore").asOption(),

                newConfiguration("hu.blackbelt.osgi.filestore.servlet.UploadServlet")
                        .put("cors.allowOrigin", TEST_ORIGIN)
                        .put("servletPath", "/upload").asOption(),
                newConfiguration("hu.blackbelt.osgi.filestore.servlet.DownloadServlet")
                        .put("cors.allowOrigin", TEST_ORIGIN)
                        .put("servletPath", "/download").asOption(),

                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultKeyProvider").asOption(),

                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultTokenIssuer")
                        .put("issuer", issuer).asOption(),
                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultTokenValidator")
                        .put("issuer", issuer).asOption()
        );
    }

    HttpClient client;

    @Before
    public void setUp() {
        client = HttpClientBuilder.create().build();
    }

    @Test
    @SneakyThrows
    public void testTokenServices() {
        final Map<String, Object> context = new TreeMap<>();
        context.put("ACTOR", "AdminActor");
        context.put("USER", "testuser");
        final String contextString = JsonUtil.toJson(context);

        final Token<UploadClaim> pdfUploadToken = Token.<UploadClaim>builder()
                .jwtClaim(UploadClaim.FILE_MIME_TYPE_LIST, "application/pdf")
                .jwtClaim(UploadClaim.CONTEXT, contextString)
                .build();
        final Token<UploadClaim> textUploadToken = Token.<UploadClaim>builder()
                .jwtClaim(UploadClaim.FILE_MIME_TYPE_LIST, "application/pdf,text/plain,application/vnd.oasis.opendocument.text,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .jwtClaim(UploadClaim.CONTEXT, contextString)
                .build();

        final String pdfUploadTokenString = tokenIssuer.createUploadToken(pdfUploadToken);
        log.info("Created upload token for PDF files: {}", pdfUploadTokenString);
        final String textUploadTokenString = tokenIssuer.createUploadToken(textUploadToken);
        log.info("Created upload token for text files: {}", textUploadTokenString);
        final Token<UploadClaim> decodedUploadToken = tokenValidator.parseUploadToken(pdfUploadTokenString);
        log.info("Decoded upload token for PDF files: {}", decodedUploadToken);
        assertThat(decodedUploadToken, equalTo(pdfUploadToken));

        final HttpResponse uploadPreflight1 = client.execute(getPrefligthRequest(TEST_ORIGIN, TEST_METHOD, TEST_HEADERS, "/upload"));
        assertThat(uploadPreflight1.getStatusLine().getStatusCode(), equalTo(200));
        assertThat(uploadPreflight1.getFirstHeader("Access-Control-Allow-Origin").getValue(), equalTo(TEST_ORIGIN));
        assertThat(uploadPreflight1.getFirstHeader("Access-Control-Allow-Credentials").getValue(), equalTo("true"));
        assertThat(uploadPreflight1.getFirstHeader("Access-Control-Allow-Methods").getValue(), equalTo(TEST_METHOD));
        assertThat(uploadPreflight1.getFirstHeader("Access-Control-Allow-Headers").getValue(), equalTo(TEST_HEADERS.stream().collect(Collectors.joining(","))));
        assertThat(uploadPreflight1.getFirstHeader("Access-Control-Max-Age").getValue(), equalTo("-1"));

        assertThat(client.execute(getPrefligthRequest("http://www.invalid.com", TEST_METHOD, TEST_HEADERS, "/upload")).getStatusLine().getStatusCode(), equalTo(400));
        assertThat(client.execute(getPrefligthRequest(TEST_ORIGIN, "POST", TEST_HEADERS, "/upload")).getStatusLine().getStatusCode(), equalTo(200));
        assertThat(client.execute(getPrefligthRequest(TEST_ORIGIN, "DELETE", TEST_HEADERS, "/upload")).getStatusLine().getStatusCode(), equalTo(400));
        assertThat(client.execute(getPrefligthRequest(TEST_ORIGIN, TEST_METHOD, Collections.singleton("X-Invalid"), "/upload")).getStatusLine().getStatusCode(), equalTo(400));

        final HttpPost uploadRequestWithoutToken = getUploadRequest(null, TEST_ORIGIN, false);
        assertThat(execute(uploadRequestWithoutToken), equalTo(HttpStatus.SC_FORBIDDEN));

        final HttpPost uploadRequestWithInvalidToken = getUploadRequest(pdfUploadTokenString, TEST_ORIGIN, false);
        final HttpResponse uploadResponseWithInvalidToken = client.execute(uploadRequestWithInvalidToken);
        final String uploadResponseMessageWithInvalidToken = IOUtils.toString(uploadResponseWithInvalidToken.getEntity().getContent());
        log.info("Response: {}\n{}", uploadResponseWithInvalidToken, uploadResponseMessageWithInvalidToken);
        assertThat(uploadResponseWithInvalidToken.getStatusLine().getStatusCode(), equalTo(HttpStatus.SC_OK));
        assertThat(uploadResponseMessageWithInvalidToken, stringContainsInOrder(Arrays.asList("Invalid MIME type: text/plain, expected: [application/pdf]")));

        final HttpPost uploadRequestWithInvalidOrigin = getUploadRequest(textUploadTokenString, "http://www.invalid.com", true);
        assertThat(execute(uploadRequestWithInvalidOrigin), equalTo(HttpStatus.SC_BAD_REQUEST));

        final HttpPost uploadRequest = getUploadRequest(textUploadTokenString, null, true);
        final HttpResponse uploadResponse = client.execute(uploadRequest);
        final String uploadResponseMessage = IOUtils.toString(uploadResponse.getEntity().getContent());
        final Map<String, Object> uploadResponseJson = JsonUtil.parseJson(uploadResponseMessage);
        log.info("Response: {}\n{}", uploadResponse, uploadResponseJson);
        assertThat(uploadResponse.getStatusLine().getStatusCode(), equalTo(HttpStatus.SC_OK));
        final List<Map<String, Object>> filesOfUploadResponse = (List<Map<String, Object>>) uploadResponseJson.get("files");
        final String id = (String) filesOfUploadResponse.get(0).get("id");
        final String downloadTokenString = (String) filesOfUploadResponse.get(0).get("token");
        final Token<DownloadClaim> decodedDownloadToken = tokenValidator.parseDownloadToken(downloadTokenString);
        log.info("Decoded download token: {}", decodedDownloadToken);
        assertThat(decodedDownloadToken.get(DownloadClaim.FILE_NAME), equalTo("sample.txt"));
        assertThat(decodedDownloadToken.get(DownloadClaim.FILE_MIME_TYPE), equalTo("text/plain"));
        assertThat(decodedDownloadToken.get(DownloadClaim.FILE_SIZE), equalTo((long) getSampleTextBytes().length));
        assertThat(decodedDownloadToken.get(DownloadClaim.CONTEXT), notNullValue());
        assertThat(JsonUtil.parseJson((String) decodedDownloadToken.get(DownloadClaim.CONTEXT)), equalTo(context));

        final HttpResponse downloadPreflight1 = client.execute(getPrefligthRequest(TEST_ORIGIN, TEST_METHOD, TEST_HEADERS, "/download"));
        assertThat(downloadPreflight1.getStatusLine().getStatusCode(), equalTo(200));
        assertThat(downloadPreflight1.getFirstHeader("Access-Control-Allow-Origin").getValue(), equalTo(TEST_ORIGIN));
        assertThat(downloadPreflight1.getFirstHeader("Access-Control-Allow-Credentials").getValue(), equalTo("true"));
        assertThat(downloadPreflight1.getFirstHeader("Access-Control-Allow-Methods").getValue(), equalTo(TEST_METHOD));
        assertThat(downloadPreflight1.getFirstHeader("Access-Control-Allow-Headers").getValue(), equalTo(TEST_HEADERS.stream().collect(Collectors.joining(","))));
        assertThat(downloadPreflight1.getFirstHeader("Access-Control-Max-Age").getValue(), equalTo("-1"));

        assertThat(client.execute(getPrefligthRequest("http://www.invalid.com", TEST_METHOD, TEST_HEADERS, "/download")).getStatusLine().getStatusCode(), equalTo(400));
        assertThat(client.execute(getPrefligthRequest(TEST_ORIGIN, "POST", TEST_HEADERS, "/download")).getStatusLine().getStatusCode(), equalTo(400));
        assertThat(client.execute(getPrefligthRequest(TEST_ORIGIN, TEST_METHOD, Collections.singleton("X-Invalid"), "/download")).getStatusLine().getStatusCode(), equalTo(400));

        final HttpGet downloadRequestWithoutToken = getDownloadRequest(null, TEST_ORIGIN, null);
        assertThat(execute(downloadRequestWithoutToken), equalTo(HttpStatus.SC_FORBIDDEN));

        final HttpGet downloadRequestWithoutId = getDownloadRequest(downloadTokenString, TEST_ORIGIN, UUID.randomUUID().toString());
        assertThat(execute(downloadRequestWithoutId), equalTo(HttpStatus.SC_FORBIDDEN));

        final HttpGet downloadRequestWithInvalidOrigin = getDownloadRequest(downloadTokenString, "http://www.invalid.com", id);
        assertThat(execute(downloadRequestWithInvalidOrigin), equalTo(HttpStatus.SC_BAD_REQUEST));

        final HttpGet downloadRequest = getDownloadRequest(downloadTokenString, TEST_ORIGIN, id);
        final HttpResponse downloadResponse = client.execute(downloadRequest);
        assertThat(Optional.ofNullable(downloadResponse.getFirstHeader("Content-Disposition")).map(h -> h.getValue()).orElse(null), equalTo("attachment; filename=\"" + decodedDownloadToken.get(DownloadClaim.FILE_NAME) + "\""));
        assertThat(downloadResponse.getEntity().getContentLength(), equalTo(decodedDownloadToken.get(DownloadClaim.FILE_SIZE)));
        assertThat(Optional.ofNullable(downloadResponse.getEntity().getContentType()).map(h -> h.getValue()).orElse(null), stringContainsInOrder(Arrays.asList((String) decodedDownloadToken.get(DownloadClaim.FILE_MIME_TYPE))));
        final String downloadResponseContent = IOUtils.toString(downloadResponse.getEntity().getContent());
        log.info("Response: {}\n{}", downloadResponse, downloadResponseContent);
        assertThat(downloadResponseContent, equalTo(new String(getSampleTextBytes())));
        assertThat(downloadResponse.getStatusLine().getStatusCode(), equalTo(HttpStatus.SC_OK));

        // test downloading with new token
        final Token<DownloadClaim> downloadToken2 = Token.<DownloadClaim>builder()
                .jwtClaim(DownloadClaim.FILE_ID, decodedDownloadToken.get(DownloadClaim.FILE_ID))
                .jwtClaim(DownloadClaim.DISPOSITION, "attachment")
                .build();
        final String downloadToken2String = tokenIssuer.createDownloadToken(downloadToken2);

        final HttpGet downloadRequest2 = getDownloadRequest(downloadToken2String, null, null);
        final HttpResponse downloadResponse2 = client.execute(downloadRequest2);
        assertThat(Optional.ofNullable(downloadResponse2.getFirstHeader("Content-Disposition")).map(h -> h.getValue()).orElse(null), equalTo("attachment; filename=\"" + decodedDownloadToken.get(DownloadClaim.FILE_NAME) + "\""));
        assertThat(downloadResponse2.getEntity().getContentLength(), equalTo(decodedDownloadToken.get(DownloadClaim.FILE_SIZE)));
        assertThat(Optional.ofNullable(downloadResponse2.getEntity().getContentType()).map(h -> h.getValue()).orElse(null), stringContainsInOrder(Arrays.asList((String) decodedDownloadToken.get(DownloadClaim.FILE_MIME_TYPE))));
        final String downloadResponseContent2 = IOUtils.toString(downloadResponse2.getEntity().getContent());
        log.info("Response: {}\n{}", downloadResponse2, downloadResponseContent2);
        assertThat(downloadResponseContent2, equalTo(new String(getSampleTextBytes())));
        assertThat(downloadResponse2.getStatusLine().getStatusCode(), equalTo(HttpStatus.SC_OK));
    }

    private static byte[] getSampleTextBytes() {
        return "árvíztűrő tükörfúrógép".getBytes(StandardCharsets.UTF_8);
    }

    private static InputStream getSampleTextInputStream() {
        return new ByteArrayInputStream(getSampleTextBytes());
    }

    private HttpOptions getPrefligthRequest(final String origin, final String requestMethod, final Collection<String> requestHeaders, final String path) {
        final HttpOptions request = new HttpOptions("http://localhost:8181" + path);
        request.setHeader("Origin", origin);
        request.setHeader("Access-Control-Request-Method", requestMethod);
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            request.setHeader("Access-Control-Request-Headers", requestHeaders.stream().collect(Collectors.joining(",")));
        }
        return request;
    }

    private HttpPost getUploadRequest(final String token, final String origin, final boolean setContentLength) {
        final HttpPost uploadRequest = new HttpPost("http://localhost:8181/upload");
        if (origin != null) {
            uploadRequest.setHeader("Origin", origin);
        }
        final MultipartEntityBuilder uploadRequestEntity = MultipartEntityBuilder.create();
        uploadRequestEntity.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
        if (setContentLength) {
            // using addPart(String, InputStreamBody) instead of addBinaryBody so Content-Length HTTP header is set
            uploadRequestEntity.addPart("upstream", new InputStreamBody(getSampleTextInputStream(), ContentType.create("text/plain"), "sample.txt") {
                @Override
                public long getContentLength() {
                    return getSampleTextBytes().length;
                }
            });
        } else {
            uploadRequestEntity.addBinaryBody("upstream", getSampleTextInputStream(), ContentType.create("text/plain"), "sample.txt");
        }
        uploadRequest.setEntity(uploadRequestEntity.build());
        if (token != null) {
            uploadRequest.setHeader("X-Token", token);
        }

        return uploadRequest;
    }

    private HttpGet getDownloadRequest(final String token, final String origin, final String id) {
        final HttpGet downloadRequest = new HttpGet("http://localhost:8181/download" + (id != null ? "?id=" + id : ""));
        if (token != null) {
            downloadRequest.setHeader("X-Token", token);
        }
        if (origin != null) {
            downloadRequest.setHeader("Origin", origin);
        }

        return downloadRequest;
    }

    private int execute(final HttpUriRequest request) throws IOException {
        final HttpResponse response = client.execute(request);
        log.info("Response: {}\n{}", response, IOUtils.toString(response.getEntity().getContent()));
        return response.getStatusLine().getStatusCode();
    }
}
