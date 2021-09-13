package hu.blackbelt.osgi.filestore.itest;

import hu.blackbelt.osgi.filestore.security.api.Token;
import hu.blackbelt.osgi.filestore.security.api.TokenIssuer;
import hu.blackbelt.osgi.filestore.security.api.TokenValidator;
import hu.blackbelt.osgi.filestore.security.api.UploadClaim;
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

import static hu.blackbelt.osgi.filestore.itest.utils.TestUtil.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.OptionUtils.combine;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.newConfiguration;
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

                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultTokenIssuer")
                        .put("secret", secret)
                        .put("issuer", issuer).asOption(),
                newConfiguration("hu.blackbelt.osgi.filestore.security.DefaultTokenValidator")
                        .put("secret", secret)
                        .put("issuer", issuer).asOption()
        );
    }

    @Test
    public void testUploadAndDownload() {
        final Token<UploadClaim> uploadToken = Token.<UploadClaim>builder()
                .jwtClaim(UploadClaim.FILE_MIME_TYPE, "application/pdf")
                .build();

        final String uploadTokenString = tokenIssuer.createUploadToken(uploadToken);
        log.debug("Created upload token: {}", uploadTokenString);
        final Token<UploadClaim> decodedUploadToken = tokenValidator.parseUploadToken(uploadTokenString);
        log.debug("Decoded upload token: {}", decodedUploadToken);
        assertThat(decodedUploadToken, equalTo(uploadToken));
    }
}
