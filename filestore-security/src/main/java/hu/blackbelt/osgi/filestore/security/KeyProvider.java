package hu.blackbelt.osgi.filestore.security;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jose4j.jwk.*;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.keys.EllipticCurves;
import org.jose4j.keys.HmacKey;
import org.jose4j.lang.JoseException;

import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.ECParameterSpec;
import java.util.Base64;

@Getter
@Slf4j
public class KeyProvider {

    private static final int GENERATED_RSA_KEY_SIZE = 2048;
    private static final ECParameterSpec GENERATED_EC_KEY_SPEC = EllipticCurves.P521;
    private static final int GENERATED_SECRET_KEY_SIZE = 1024;

    private Key publicKey;
    private Key privateKey;

    public KeyProvider(final TokenServiceConfig config) {
        if (config.algorithm().startsWith("HS")) {
            loadHMACKey(config);
        } else if (config.algorithm().startsWith("RS")) {
            loadRSAKey(config);
        } else if (config.algorithm().startsWith("ES")) {
            loadECKey(config);
        } else if (config.algorithm().startsWith("PS")) {
            loadRSAKey(config);
        } else if (!AlgorithmIdentifiers.NONE.equals(config.algorithm())) {
            throw new UnsupportedOperationException("Unsupported JWT algorithm: " + config.algorithm());
        }
    }

    private void loadRSAKey(TokenServiceConfig config) {
        try {
            if (config.keys() != null && !"".equals(config.keys())) {
                log.info("Loading RSA key pair...");
                final PublicJsonWebKey jsonWebKey = PublicJsonWebKey.Factory.newPublicJwk(new String(Base64.getDecoder().decode(config.keys())));
                privateKey = jsonWebKey.getPrivateKey();
                publicKey = jsonWebKey.getPublicKey();
            } else {
                log.info("Generating RSA key pair...");
                final RsaJsonWebKey rsaJsonWebKey = RsaJwkGenerator.generateJwk(GENERATED_RSA_KEY_SIZE);
                if (log.isTraceEnabled()) {
                    log.trace("Generated RSA key: {}", rsaJsonWebKey.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE));
                }
                privateKey = rsaJsonWebKey.getPrivateKey();
                publicKey = rsaJsonWebKey.getPublicKey();
            }
        } catch (JoseException ex) {
            throw new IllegalStateException("Unable to initialize RSA key", ex);
        }
    }

    private void loadECKey(TokenServiceConfig config) {
        try {
            if (config.keys() != null && !"".equals(config.keys())) {
                log.info("Loading EC key pair...");
                final PublicJsonWebKey jsonWebKey = PublicJsonWebKey.Factory.newPublicJwk(new String(Base64.getDecoder().decode(config.keys())));
                privateKey = jsonWebKey.getPrivateKey();
                publicKey = jsonWebKey.getPublicKey();
            } else {
                log.info("Generating EC key pair...");
                final EllipticCurveJsonWebKey ellipticCurveJsonWebKey = EcJwkGenerator.generateJwk(GENERATED_EC_KEY_SPEC);
                if (log.isTraceEnabled()) {
                    log.trace("Generated EC key: {}", ellipticCurveJsonWebKey.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE));
                }
                privateKey = ellipticCurveJsonWebKey.getEcPrivateKey();
                publicKey = ellipticCurveJsonWebKey.getECPublicKey();
            }
        } catch (JoseException ex) {
            throw new IllegalStateException("Unable to initialize EC key", ex);
        }
    }

    private void loadHMACKey(TokenServiceConfig config) {
        if (config.secret() != null && !"".equals(config.secret())) {
            log.info("Loading HMAC secret...");
            privateKey = new HmacKey(Base64.getDecoder().decode(config.secret()));
            publicKey = privateKey;
        } else {
            try {
                log.info("Generating HMAC secret...");
                final SecureRandom random = SecureRandom.getInstanceStrong();
                final byte[] values = new byte[GENERATED_SECRET_KEY_SIZE / 8];
                random.nextBytes(values);
                if (log.isTraceEnabled()) {
                    log.trace("Generated secret: {}", Base64.getEncoder().encodeToString(values));
                }
                privateKey = new HmacKey(values);
                publicKey = privateKey;
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("Unable to initialize identifier signer");
            }
        }
    }
}
