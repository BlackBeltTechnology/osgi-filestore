package hu.blackbelt.osgi.filestore.itest;

import hu.blackbelt.osgi.filestore.security.api.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;

import javax.inject.Inject;
import java.time.OffsetDateTime;
import java.util.UUID;

import static hu.blackbelt.osgi.filestore.itest.utils.TestUtil.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.OptionUtils.combine;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.newConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFilePut;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
@Slf4j
public class FilestoreTest {

    public static final String FEATURE_FILESTORE_SECURITY = "filestore-security";

    @Inject
    TokenIssuer tokenIssuer;

    @Inject
    TokenValidator tokenValidator;

    @Configuration
    @SneakyThrows
    public Option[] config() {
        final String issuer = "Issuer";
        final String secret = "c85830cff937442bf5993cb8f6279122cf619685802557db881c8a16825c92b33474697015ae9c50fd441c9d950d2f4c70b2ec01c17e218c93625a456997ce4c";

        return combine(karafConfig(this.getClass()),

                systemProperty("org.ops4j.pax.exam.raw.extender.intern.Parser.DEFAULT_TIMEOUT").value("30000"),
                systemProperty("pax.exam.service.timeout").value("30000"),

                features(karafStandardRepo()),
                features(filestoreArtifact(), FEATURE_FILESTORE_SECURITY),

                editConfigurationFilePut("etc/org.ops4j.pax.web.cfg", "org.osgi.service.http.port", "8181"),

                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultTokenIssuer")
                        .put("secret", secret)
                        .put("issuer", issuer).asOption(),
                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultTokenValidator")
                        .put("secret", secret)
                        .put("issuer", issuer).asOption()
        );
    }

    @Test
    public void testTokenServices() {
        final Token<UploadClaim> uploadToken = Token.<UploadClaim>builder()
                .jwtClaim(UploadClaim.FILE_MIME_TYPE, "application/pdf")
                .build();

        final String uploadTokenString = tokenIssuer.createUploadToken(uploadToken);
        log.info("Created upload token: {}", uploadTokenString);
        final Token<UploadClaim> decodedUploadToken = tokenValidator.parseUploadToken(uploadTokenString);
        log.info("Decoded upload token: {}", decodedUploadToken);
        assertThat(decodedUploadToken, equalTo(uploadToken));

        final Token<DownloadClaim> downloadToken = Token.<DownloadClaim>builder()
                .jwtClaim(DownloadClaim.FILE_MIME_TYPE, "application/pdf")
                .jwtClaim(DownloadClaim.FILE_ID, UUID.randomUUID().toString())
                .jwtClaim(DownloadClaim.FILE_CREATED, OffsetDateTime.now())
                .jwtClaim(DownloadClaim.FILE_NAME, "test.pdf")
                .jwtClaim(DownloadClaim.FILE_SIZE, 10000L)
                .jwtClaim(DownloadClaim.DISPOSITION, "attachment")
                .build();

        final String downloadTokenString = tokenIssuer.createDownloadToken(downloadToken);
        log.info("Created download token: {}", downloadTokenString);
        final Token<DownloadClaim> decodedDownloadToken = tokenValidator.parseDownloadToken(downloadTokenString);
        log.info("Decoded download token: {}", decodedDownloadToken);
        assertThat(decodedDownloadToken, equalTo(downloadToken));
    }
}
