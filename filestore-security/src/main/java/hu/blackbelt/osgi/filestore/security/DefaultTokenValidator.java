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
import hu.blackbelt.osgi.filestore.security.api.TokenValidator;
import hu.blackbelt.osgi.filestore.security.api.UploadClaim;
import hu.blackbelt.osgi.filestore.security.api.exceptions.InvalidTokenException;
import lombok.extern.slf4j.Slf4j;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

@Designate(ocd = TokenServiceConfig.class)
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Slf4j
public class DefaultTokenValidator implements TokenValidator {

    private String algorithm;

    @Reference
    private KeyProvider keyProvider;
    private String issuers;
    private String audiencePrefix;
    private int expirationTimeInMinutes;

    @Activate
    void start(TokenServiceConfig config) {
        algorithm = config.algorithm();
        issuers = config.issuer();
        audiencePrefix = config.audiencePrefix();
        expirationTimeInMinutes = config.expirationTime();
    }

    private Map<String, Object> parseToken(final String tokenString, final String audience) throws InvalidTokenException {
        final Map<String, Object> claims;
        if (tokenString != null && !tokenString.trim().isEmpty()) {
            JwtConsumerBuilder jwtConsumerBuilder = new JwtConsumerBuilder()
                    .setRelaxVerificationKeyValidation()
                    .setRequireSubject()
                    .setVerificationKey(keyProvider.getPublicKey())
                    .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, algorithm);
            if (expirationTimeInMinutes > 0) {
                jwtConsumerBuilder = jwtConsumerBuilder.setRequireExpirationTime();
            }
            if (issuers != null) {
                jwtConsumerBuilder = jwtConsumerBuilder.setExpectedIssuers(true, issuers.trim().split("\\s*,\\s*"));
            }
            if (audience != null) {
                jwtConsumerBuilder.setExpectedAudience((audiencePrefix != null ? audiencePrefix : "") + audience);
            }
            final JwtConsumer jwtConsumer = jwtConsumerBuilder.build();

            try {
                final JwtClaims jwtClaims = jwtConsumer.processToClaims(tokenString.trim());
                claims = jwtClaims.getClaimsMap();
            } catch (InvalidJwtException e) {
                log.debug("Invalid JWT token: {}", e.getErrorDetails());
                throw new InvalidTokenException(e);
            }
        } else {
            claims = Collections.emptyMap();
        }
        return claims;
    }

    @Override
    public Token<UploadClaim> parseUploadToken(final String tokenString) throws InvalidTokenException {
        if (tokenString == null) {
            return null;
        }
        return Token.<UploadClaim>builder()
                .jwtClaims(parseToken(tokenString, UploadClaim.AUDIENCE).entrySet().stream()
                        .filter(e -> UploadClaim.getByJwtClaimName(e.getKey()) != null && e.getValue() != null)
                        .collect(Collectors.toMap(e -> UploadClaim.getByJwtClaimName(e.getKey()), e -> UploadClaim.getByJwtClaimName(e.getKey()).convert(String.valueOf(e.getValue())))))
                .build();
    }

    @Override
    public Token<DownloadClaim> parseDownloadToken(final String tokenString) throws InvalidTokenException {
        if (tokenString == null) {
            return null;
        }
        return Token.<DownloadClaim>builder()
                .jwtClaims(parseToken(tokenString, DownloadClaim.AUDIENCE).entrySet().stream()
                        .filter(e -> DownloadClaim.getByJwtClaimName(e.getKey()) != null && e.getValue() != null)
                        .collect(Collectors.toMap(e -> DownloadClaim.getByJwtClaimName(e.getKey()), e -> DownloadClaim.getByJwtClaimName(e.getKey()).convert(String.valueOf(e.getValue())))))
                .build();
    }
}
