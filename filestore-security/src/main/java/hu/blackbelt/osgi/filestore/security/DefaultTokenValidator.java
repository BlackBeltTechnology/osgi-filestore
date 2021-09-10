package hu.blackbelt.osgi.filestore.security;

import hu.blackbelt.osgi.filestore.security.api.*;
import lombok.extern.slf4j.Slf4j;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.metatype.annotations.Designate;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.function.Function.identity;

@Designate(ocd = TokenServiceConfig.class)
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Slf4j
public class DefaultTokenValidator implements TokenValidator {

    private String algorithm;
    private KeyProvider keyProvider;
    private String issuers;
    private String audiencePrefix;
    private int expirationTimeInMinutes;

    @Activate
    void start(TokenServiceConfig config) {
        keyProvider = new KeyProvider(config);
        algorithm = config.algorithm();
        issuers = config.issuer();
        audiencePrefix = config.audiencePrefix();
        expirationTimeInMinutes = config.expirationTime();
    }

    private Map<String, Object> parseToken(final String tokenString, final String audience) {
        final Map<String, Object> claims;
        if (tokenString != null && tokenString.trim().isEmpty()) {
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
                throw new IllegalStateException("Invalid token", e);
            }
        } else {
            claims = Collections.emptyMap();
        }
        return claims;
    }

    @Override
    public Token<UploadClaim> parseUploadToken(final String tokenString) {
        return Token.<UploadClaim>builder()
                .jwtClaims(parseToken(tokenString, UploadClaim.AUDIENCE).entrySet().stream()
                        .filter(e -> UploadClaim.getByJwtClaimName(e.getKey()) != null)
                        .collect(Collectors.toMap(e -> UploadClaim.getByJwtClaimName(e.getKey()), identity())))
                .build();
    }

    @Override
    public Token<DownloadClaim> parseDownloadToken(final String tokenString) {
        return Token.<DownloadClaim>builder()
                .jwtClaims(parseToken(tokenString, DownloadClaim.AUDIENCE).entrySet().stream()
                        .filter(e -> DownloadClaim.getByJwtClaimName(e.getKey()) != null)
                        .collect(Collectors.toMap(e -> DownloadClaim.getByJwtClaimName(e.getKey()), identity())))
                .build();
    }
}
