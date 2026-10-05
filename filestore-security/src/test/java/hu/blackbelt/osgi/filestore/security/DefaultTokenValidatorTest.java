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
import hu.blackbelt.osgi.filestore.security.api.exceptions.InvalidTokenException;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static hu.blackbelt.osgi.filestore.security.TestSupport.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultTokenValidatorTest {

    private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";
    private static final String ALGORITHM = "HS512";

    private static Map<String, Object> cfg(Object... keysAndValues) {
        final Map<String, Object> values = new HashMap<>();
        values.put("issuer", "filestore");
        values.put("audiencePrefix", "");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return values;
    }

    private DefaultTokenValidator validator(Map<String, Object> values) throws Exception {
        final DefaultTokenValidator validator = new DefaultTokenValidator();
        inject(validator, "keyProvider", keyProvider(SECRET));
        validator.start(config(TokenServiceConfig.class, values));
        return validator;
    }

    /**
     * Signs a download token whose <code>exp</code> claim is <code>expiredSecondsAgo</code> seconds in the past,
     * or that has no <code>exp</code> claim at all when <code>expiredSecondsAgo</code> is null.
     */
    private String downloadToken(Integer expiredSecondsAgo) throws Exception {
        final JwtClaims claims = new JwtClaims();
        // a token that expired N seconds ago was issued before that, otherwise exp would precede iat
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() - (expiredSecondsAgo != null ? expiredSecondsAgo + 1800 : 0)));
        claims.setIssuer("filestore");
        claims.setAudience(DownloadClaim.AUDIENCE);
        claims.setSubject(UUID.randomUUID().toString());
        claims.setClaim(DownloadClaim.FILE_ID.getJwtClaimName(), "file-1");
        if (expiredSecondsAgo != null) {
            claims.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() - expiredSecondsAgo));
        }

        final JsonWebSignature jws = new JsonWebSignature();
        jws.setKey(keyProvider(SECRET).getPrivateKey());
        jws.setPayload(claims.toJson());
        jws.setAlgorithmHeaderValue(ALGORITHM);
        return jws.getCompactSerialization();
    }

    @Test
    void testExpiredTokenWithinClockSkewAccepted() throws Exception {
        final Token<DownloadClaim> token = validator(cfg("expirationTime", 30))
                .parseDownloadToken(downloadToken(30));

        assertThat(token.get(DownloadClaim.FILE_ID), is("file-1"));
    }

    @Test
    void testExpiredTokenBeyondClockSkewRejected() throws Exception {
        final DefaultTokenValidator validator = validator(cfg("expirationTime", 30));
        final String tokenString = downloadToken(120);

        assertThrows(InvalidTokenException.class, () -> validator.parseDownloadToken(tokenString));
    }

    @Test
    void testTokenWithoutExpirationRejectedWhenExpirationEnforced() throws Exception {
        final DefaultTokenValidator validator = validator(cfg("expirationTime", 1440));
        final String tokenString = downloadToken(null);

        assertThrows(InvalidTokenException.class, () -> validator.parseDownloadToken(tokenString));
    }

    @Test
    void testTokenWithoutExpirationAcceptedWhenExpirationDisabled() throws Exception {
        final Token<DownloadClaim> token = validator(cfg("expirationTime", 0))
                .parseDownloadToken(downloadToken(null));

        assertThat(token.get(DownloadClaim.FILE_ID), is("file-1"));
    }

    @Test
    void testNullTokenReturnsNull() throws Exception {
        assertThat(validator(cfg()).parseDownloadToken(null), nullValue());
    }
}
