package hu.blackbelt.osgi.filestore.s3;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Activation-time warning about a cleartext (http://) S3 endpoint.
 */
class S3EndpointWarningTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger root;

    @BeforeEach
    void setUp() {
        root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        appender.stop();
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    private S3FileStoreService activate(String endpoint) {
        final S3FileStoreService target = new S3FileStoreService();
        final S3FileStoreService.Config config = mock(S3FileStoreService.Config.class);
        when(config.protocol()).thenReturn("s3store");
        when(config.bucketName()).thenReturn("test-bucket");
        when(config.endpoint()).thenReturn(endpoint);
        when(config.region()).thenReturn("us-east-1");
        when(config.accessKey()).thenReturn("access");
        when(config.secretKey()).thenReturn("secret");
        target.activate(null, config);
        return target;
    }

    @Test
    void testHttpEndpointWarns() {
        final S3FileStoreService target = activate("http://minio.local:9000");

        final List<String> warnings = warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("http://minio.local:9000"));
        assertThat(target.s3Client, notNullValue());
    }

    @Test
    void testHttpsEndpointIsSilent() {
        final S3FileStoreService target = activate("https://storage.googleapis.com");

        assertThat(warnings(), is(empty()));
        assertThat(target.s3Client, notNullValue());
    }

    @Test
    void testEmptyEndpointIsSilent() {
        final S3FileStoreService target = activate("");

        assertThat(warnings(), is(empty()));
        assertThat(target.s3Client, notNullValue());
    }
}
