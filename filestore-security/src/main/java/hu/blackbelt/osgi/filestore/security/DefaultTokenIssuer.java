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
import hu.blackbelt.osgi.filestore.security.api.TokenIssuer;
import hu.blackbelt.osgi.filestore.security.api.UploadClaim;
import lombok.SneakyThrows;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import java.util.Map;
import java.util.UUID;

@Designate(ocd = TokenServiceConfig.class)
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class DefaultTokenIssuer implements TokenIssuer {

    private String algorithm;
    @Reference
    private KeyProvider keyProvider;
    private String expectedIssuers;
    private String expectedAudiencePrefix;
    private int expirationTimeInMinutes;

    @Activate
    void start(TokenServiceConfig config) {
        algorithm = config.algorithm();
        expectedIssuers = config.issuer();
        expectedAudiencePrefix = config.audiencePrefix();
        expirationTimeInMinutes = config.expirationTime();
    }

    @SneakyThrows
    private String createToken(final Map<? extends Token.Claim, Object> claims, final String audience) {
        final JwtClaims jwtClaims = new JwtClaims();

        jwtClaims.setIssuedAtToNow();
        if (expirationTimeInMinutes > 0) {
            jwtClaims.setExpirationTimeMinutesInTheFuture(expirationTimeInMinutes);
        }
        if (expectedIssuers != null) {
            jwtClaims.setIssuer(expectedIssuers.trim().split("\\s*,\\s*")[0]);
        }
        if (audience != null) {
            jwtClaims.setAudience((expectedAudiencePrefix != null ? expectedAudiencePrefix : "") + audience);
        }
        claims.forEach((claim, value) -> jwtClaims.setClaim(claim.getJwtClaimName(), value));
        if (jwtClaims.getSubject() == null) {
            jwtClaims.setSubject(UUID.randomUUID().toString());
        }

        final JsonWebSignature jws = new JsonWebSignature();
        jws.setKey(keyProvider.getPrivateKey());
        jws.setPayload(jwtClaims.toJson());
        jws.setAlgorithmHeaderValue(algorithm);

        try {
            return jws.getCompactSerialization();
        } catch (JoseException ex) {
            throw new IllegalStateException("Unable to sign identifier", ex);
        }
    }

    @Override
    public String createUploadToken(final Token<UploadClaim> token) {
        return createToken(token.getJwtClaims(), UploadClaim.AUDIENCE);
    }

    @Override
    public String createDownloadToken(final Token<DownloadClaim> token) {
        return createToken(token.getJwtClaims(), DownloadClaim.AUDIENCE);
    }
}
