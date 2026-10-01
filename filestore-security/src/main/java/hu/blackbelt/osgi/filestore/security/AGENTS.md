# DOX — filestore-security/src/main/java/hu/blackbelt/osgi/filestore/security

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `DefaultKeyProvider.java` | DS component loading the signing/verification key (RSA, EC or HMAC per configured algorithm). |
| `DefaultTokenIssuer.java` | jose4j-based `TokenIssuer`; builds and signs upload/download JWTs with audience + claims. |
| `DefaultTokenValidator.java` | jose4j-based `TokenValidator`; verifies signature/audience and maps JWT claims back to `Token`. |
| `KeyProiderConfig.java` | OSGi metatype config for `DefaultKeyProvider` (algorithm, key material). Name typo is in the source. |
| `KeyProvider.java` | Contract: `getPublicKey` / `getPrivateKey`. |
| `TokenServiceConfig.java` | OSGi metatype config shared by token issuer and validator (algorithm, audience, expiry, etc.). |
