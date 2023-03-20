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

import lombok.extern.slf4j.Slf4j;
import org.jose4j.jwk.*;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.keys.EllipticCurves;
import org.jose4j.keys.HmacKey;
import org.jose4j.lang.JoseException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.metatype.annotations.Designate;

import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.ECParameterSpec;
import java.util.Base64;

@Slf4j
@Designate(ocd = KeyProiderConfig.class)
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class DefaultKeyProvider implements KeyProvider {

    private static final int GENERATED_RSA_KEY_SIZE = 2048;
    private static final ECParameterSpec GENERATED_EC_KEY_SPEC = EllipticCurves.P521;
    private static final int GENERATED_SECRET_KEY_SIZE = 1024;

    private Key publicKey;

    private Key privateKey;

    @Activate
    public void activate(final KeyProiderConfig config) {
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

    private void loadRSAKey(KeyProiderConfig config) {
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

    private void loadECKey(KeyProiderConfig config) {
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

    private void loadHMACKey(KeyProiderConfig config) {
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

    public Key getPublicKey() {
        return publicKey;
    }

    public Key getPrivateKey() {
        return privateKey;
    }
}
