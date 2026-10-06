package hu.blackbelt.osgi.filestore.security;

/*-
 * #%L
 * JUDO framework security for filestore
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

import hu.blackbelt.osgi.filestore.security.api.DownloadClaim;
import hu.blackbelt.osgi.filestore.security.api.Token;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static hu.blackbelt.osgi.filestore.security.TestSupport.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class DefaultTokenIssuerTest {

    private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";

    private LogCapture logs;

    @BeforeEach
    void setUp() {
        logs = new LogCapture();
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    private DefaultTokenIssuer issuer(Map<String, Object> values) throws Exception {
        final DefaultTokenIssuer issuer = new DefaultTokenIssuer();
        inject(issuer, "keyProvider", keyProvider(SECRET));
        issuer.start(config(TokenServiceConfig.class, values));
        return issuer;
    }

    private static Map<String, Object> cfg(Object... keysAndValues) {
        final Map<String, Object> values = new HashMap<>();
        values.put("issuer", "filestore");
        values.put("audiencePrefix", "");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return values;
    }

    private JwtClaims claimsOf(String tokenString) throws Exception {
        return new JwtConsumerBuilder()
                .setSkipAllValidators()
                .setDisableRequireSignature()
                .setSkipSignatureVerification()
                .build()
                .processToClaims(tokenString);
    }

    private String downloadToken(DefaultTokenIssuer issuer) {
        return issuer.createDownloadToken(Token.<DownloadClaim>builder()
                .jwtClaims(Collections.singletonMap(DownloadClaim.FILE_ID, "file-1"))
                .build());
    }

    @Test
    void testDefaultExpirationIs1440Minutes() throws Exception {
        final JwtClaims claims = claimsOf(downloadToken(issuer(cfg())));

        assertThat(claims.getExpirationTime(), notNullValue());
        final long minutes = (claims.getExpirationTime().getValue() - claims.getIssuedAt().getValue()) / 60;
        assertThat(minutes, is(1440L));
    }

    @Test
    void testExplicitZeroKeepsTokenNonExpiring() throws Exception {
        final JwtClaims claims = claimsOf(downloadToken(issuer(cfg("expirationTime", 0))));

        assertThat(claims.getExpirationTime(), nullValue());
    }

    @Test
    void testWarnsWhenExpirationDisabled() throws Exception {
        issuer(cfg("expirationTime", 0));

        final List<String> warnings = logs.warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("expirationTime"));
    }

    @Test
    void testSilentWhenExpirationEnabled() throws Exception {
        issuer(cfg("expirationTime", 1440));

        assertThat(logs.warnings(), is(empty()));
    }
}
