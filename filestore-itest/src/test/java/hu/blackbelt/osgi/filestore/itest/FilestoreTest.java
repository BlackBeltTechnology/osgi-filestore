package hu.blackbelt.osgi.filestore.itest;

import hu.blackbelt.osgi.filestore.security.api.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jose4j.json.JsonUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;
import org.ops4j.pax.exam.util.Filter;

import javax.inject.Inject;
import javax.servlet.Servlet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static hu.blackbelt.osgi.filestore.itest.utils.TestUtil.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.text.StringContainsInOrder.stringContainsInOrder;
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
    public static final String FEATURE_HTTP_CLIENT_4 = "apache-httpclient4";

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

    @Configuration
    @SneakyThrows
    public Option[] config() {
        final String issuer = "Issuer";
        final String secret = "c85830cff937442bf5993cb8f6279122cf619685802557db881c8a16825c92b33474697015ae9c50fd441c9d950d2f4c70b2ec01c17e218c93625a456997ce4c";

        return combine(karafConfig(this.getClass()),

                systemProperty("org.ops4j.pax.exam.raw.extender.intern.Parser.DEFAULT_TIMEOUT").value("120000"),
                systemProperty("pax.exam.service.timeout").value("120000"),

                features(filestoreArtifact(), FEATURE_FILESTORE_FULL),
                features(httpClientArtifact(), FEATURE_HTTP_CLIENT_4),

                editConfigurationFilePut("etc/org.ops4j.pax.web.cfg", "org.osgi.service.http.port", "8181"),

                newConfiguration("hu.blackbelt.osgi.filestore.filesystem.FileSystemFileStoreService")
                        .put("protocol", "judostore").asOption(),

                newConfiguration("hu.blackbelt.osgi.filestore.servlet.UploadServlet")
                        .put("servletPath", "/upload").asOption(),
                newConfiguration("hu.blackbelt.osgi.filestore.servlet.DownloadServlet")
                        .put("servletPath", "/download").asOption(),

                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultTokenIssuer")
                        .put("secret", secret)
                        .put("issuer", issuer).asOption(),
                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultTokenValidator")
                        .put("secret", secret)
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
        final Token<UploadClaim> pdfUploadToken = Token.<UploadClaim>builder()
                .jwtClaim(UploadClaim.FILE_MIME_TYPE_LIST, "application/pdf")
                .build();
        final Token<UploadClaim> textUploadToken = Token.<UploadClaim>builder()
                .jwtClaim(UploadClaim.FILE_MIME_TYPE_LIST, "application/pdf,text/plain,application/vnd.oasis.opendocument.text,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .build();

        final String pdfUploadTokenString = tokenIssuer.createUploadToken(pdfUploadToken);
        log.info("Created upload token for PDF files: {}", pdfUploadTokenString);
        final String textUploadTokenString = tokenIssuer.createUploadToken(textUploadToken);
        log.info("Created upload token for text files: {}", textUploadTokenString);
        final Token<UploadClaim> decodedUploadToken = tokenValidator.parseUploadToken(pdfUploadTokenString);
        log.info("Decoded upload token for PDF files: {}", decodedUploadToken);
        assertThat(decodedUploadToken, equalTo(pdfUploadToken));

        final HttpPost uploadRequestWithoutToken = getUploadRequest(null);
        assertThat(execute(uploadRequestWithoutToken), equalTo(HttpStatus.SC_FORBIDDEN));

        final HttpPost uploadRequestWithInvalidToken = getUploadRequest(pdfUploadTokenString);
        final HttpResponse uploadResponseWithInvalidToken = client.execute(uploadRequestWithInvalidToken);
        final String uploadResponseMessageWithInvalidToken = IOUtils.toString(uploadResponseWithInvalidToken.getEntity().getContent());
        log.info("Response: {}\n{}", uploadResponseWithInvalidToken, uploadResponseMessageWithInvalidToken);
        assertThat(uploadResponseWithInvalidToken.getStatusLine().getStatusCode(), equalTo(HttpStatus.SC_OK));
        assertThat(uploadResponseMessageWithInvalidToken, stringContainsInOrder(Arrays.asList("Invalid MIME type: text/plain, expected: [application/pdf]")));

        final HttpPost uploadRequest = getUploadRequest(textUploadTokenString);
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

        final HttpGet downloadRequestWithoutToken = getDownloadRequest(null, null);
        assertThat(execute(downloadRequestWithoutToken), equalTo(HttpStatus.SC_FORBIDDEN));

        final HttpGet downloadRequestWithoutId = getDownloadRequest(downloadTokenString, null);
        assertThat(execute(downloadRequestWithoutId), equalTo(HttpStatus.SC_BAD_REQUEST));

        final HttpGet downloadRequest = getDownloadRequest(downloadTokenString, id);
        final HttpResponse downloadResponse = client.execute(downloadRequest);
        assertThat(Optional.ofNullable(downloadResponse.getFirstHeader("Content-Disposition")).map(h -> h.getValue()).orElse(null), equalTo("attachment; filename=\"" + decodedDownloadToken.get(DownloadClaim.FILE_NAME) + "\""));
        assertThat(downloadResponse.getEntity().getContentLength(), equalTo(decodedDownloadToken.get(DownloadClaim.FILE_SIZE)));
        assertThat(Optional.ofNullable(downloadResponse.getEntity().getContentType()).map(h -> h.getValue()).orElse(null), stringContainsInOrder(Arrays.asList((String) decodedDownloadToken.get(DownloadClaim.FILE_MIME_TYPE))));
        final String downloadResponseContent = IOUtils.toString(downloadResponse.getEntity().getContent());
        log.info("Response: {}\n{}", downloadResponse, downloadResponseContent);
        assertThat(downloadResponseContent, equalTo(IOUtils.toString(getSampleTextInputString())));
        assertThat(downloadResponse.getStatusLine().getStatusCode(), equalTo(HttpStatus.SC_OK));
    }

    private static InputStream getSampleTextInputString() {
        return new ByteArrayInputStream("árvíztűrő tükörfúrógép".getBytes(StandardCharsets.UTF_8));
    }

    private HttpPost getUploadRequest(final String token) {
        final HttpPost uploadRequest = new HttpPost("http://localhost:8181/upload");
        final MultipartEntityBuilder uploadRequestEntity = MultipartEntityBuilder.create();
        uploadRequestEntity.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
        uploadRequestEntity.addBinaryBody("upstream", getSampleTextInputString(), ContentType.create("text/plain"), "sample.txt");
        uploadRequest.setEntity(uploadRequestEntity.build());
        if (token != null) {
            uploadRequest.setHeader("X-Token", token);
        }

        return uploadRequest;
    }

    private HttpGet getDownloadRequest(final String token, final String id) {
        final HttpGet downloadRequest = new HttpGet("http://localhost:8181/download" + (id != null ? "?id=" + id : ""));
        if (token != null) {
            downloadRequest.setHeader("X-Token", token);
        }

        return downloadRequest;
    }

    private int execute(final HttpUriRequest request) throws IOException {
        final HttpResponse response = client.execute(request);
        log.info("Response: {}\n{}", response, IOUtils.toString(response.getEntity().getContent()));
        return response.getStatusLine().getStatusCode();
    }
}
