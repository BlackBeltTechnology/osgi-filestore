# Filestore Security Specification

## Purpose

Provides JWT-based token issuance and validation for securing filestore upload and download operations, using jose4j for cryptographic operations and OSGi Declarative Services for lifecycle management.

## Architecture

- **`KeyProvider`** -- Interface declaring `getPublicKey()` and `getPrivateKey()` methods returning `java.security.Key`.
- **`DefaultKeyProvider`** -- `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)` implementing `KeyProvider`. Configured via `@Designate(ocd = KeyProiderConfig.class)`. Supports HMAC (HS*), RSA (RS*, PS*), and EC (ES*) algorithm families. Loads keys from Base64-encoded configuration or auto-generates them.
- **`DefaultTokenIssuer`** -- `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)` implementing `TokenIssuer`. Configured via `@Designate(ocd = TokenServiceConfig.class)`. Holds a `@Reference` to `KeyProvider`. Creates signed JWTs with configurable issuer, audience prefix, and expiration time.
- **`DefaultTokenValidator`** -- `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)` implementing `TokenValidator`. Configured via `@Designate(ocd = TokenServiceConfig.class)`. Holds a `@Reference` to `KeyProvider`. Parses and validates JWTs, enforcing algorithm constraints, issuer, audience, and optional expiration.
- **`KeyProiderConfig`** -- `@ObjectClassDefinition` annotation interface with `algorithm()` (default `"HS512"`), `secret()` (Base64-encoded HMAC secret), and `keys()` (Base64-encoded JSON Web Key for RSA/EC).
- **`TokenServiceConfig`** -- `@ObjectClassDefinition` annotation interface with `algorithm()` (default `"HS512"`), `issuer()` (comma-separated), `audiencePrefix()`, and `expirationTime()` (minutes, default 0 meaning no expiration).

### Dependency Graph

```
DefaultTokenIssuer ---@Reference---> KeyProvider <--- DefaultKeyProvider
DefaultTokenValidator ---@Reference---> KeyProvider
```

## Requirements

### Requirement: HMAC Key Provisioning

The `DefaultKeyProvider` SHALL support HMAC symmetric key provisioning when the configured `algorithm` starts with `"HS"`. If `KeyProiderConfig.secret()` is provided and non-empty, the key SHALL be loaded by Base64-decoding the secret into a `HmacKey`. If no secret is provided, a 1024-bit key SHALL be generated using `SecureRandom.getInstanceStrong()`. For HMAC, `getPublicKey()` and `getPrivateKey()` SHALL return the same `HmacKey` instance.

#### Scenario: HMAC key loaded from configuration
- **GIVEN** a `KeyProiderConfig` with `algorithm = "HS512"` and `secret` set to a valid Base64-encoded byte array
- **WHEN** `DefaultKeyProvider.activate(config)` is called
- **THEN** `getPrivateKey()` returns a `HmacKey` decoded from the provided secret, and `getPublicKey()` returns the same key instance

#### Scenario: HMAC key auto-generated
- **GIVEN** a `KeyProiderConfig` with `algorithm = "HS256"` and `secret` is null or empty
- **WHEN** `DefaultKeyProvider.activate(config)` is called
- **THEN** `getPrivateKey()` returns a randomly generated `HmacKey` of 1024 bits, and `getPublicKey()` returns the same key instance

### Requirement: RSA Key Provisioning

The `DefaultKeyProvider` SHALL support RSA asymmetric key provisioning when the configured `algorithm` starts with `"RS"` or `"PS"`. If `KeyProiderConfig.keys()` is provided and non-empty, the key pair SHALL be loaded by Base64-decoding the value and parsing it as a `PublicJsonWebKey`. If no keys are provided, a 2048-bit RSA key pair SHALL be generated using `RsaJwkGenerator.generateJwk(2048)`.

#### Scenario: RSA key pair loaded from configuration
- **GIVEN** a `KeyProiderConfig` with `algorithm = "RS256"` and `keys` set to a Base64-encoded JWK JSON string containing both public and private RSA keys
- **WHEN** `DefaultKeyProvider.activate(config)` is called
- **THEN** `getPublicKey()` returns the RSA public key from the JWK, and `getPrivateKey()` returns the RSA private key from the JWK

#### Scenario: RSA key pair auto-generated
- **GIVEN** a `KeyProiderConfig` with `algorithm = "RS512"` and `keys` is null or empty
- **WHEN** `DefaultKeyProvider.activate(config)` is called
- **THEN** `getPublicKey()` and `getPrivateKey()` return a freshly generated 2048-bit RSA key pair

### Requirement: EC Key Provisioning

The `DefaultKeyProvider` SHALL support Elliptic Curve asymmetric key provisioning when the configured `algorithm` starts with `"ES"`. If `KeyProiderConfig.keys()` is provided and non-empty, the key pair SHALL be loaded by Base64-decoding and parsing as a `PublicJsonWebKey`. If no keys are provided, an EC key pair SHALL be generated using `EcJwkGenerator.generateJwk(EllipticCurves.P521)`.

#### Scenario: EC key pair auto-generated
- **GIVEN** a `KeyProiderConfig` with `algorithm = "ES512"` and `keys` is null or empty
- **WHEN** `DefaultKeyProvider.activate(config)` is called
- **THEN** `getPublicKey()` returns an EC public key on the P-521 curve, and `getPrivateKey()` returns the corresponding EC private key

### Requirement: Unsupported Algorithm Rejection

The `DefaultKeyProvider` SHALL throw an `UnsupportedOperationException` with a message containing the algorithm name when the configured `algorithm` does not start with `"HS"`, `"RS"`, `"ES"`, or `"PS"`, and is not equal to `AlgorithmIdentifiers.NONE`.

#### Scenario: Unknown algorithm configured
- **GIVEN** a `KeyProiderConfig` with `algorithm = "XY999"`
- **WHEN** `DefaultKeyProvider.activate(config)` is called
- **THEN** an `UnsupportedOperationException` is thrown with message `"Unsupported JWT algorithm: XY999"`

### Requirement: Token Issuance

The `DefaultTokenIssuer` SHALL create signed JWT strings for upload and download tokens. The `createUploadToken(Token<UploadClaim>)` method SHALL produce a JWT with audience `"Upload"` (prefixed by `audiencePrefix` if configured). The `createDownloadToken(Token<DownloadClaim>)` method SHALL produce a JWT with audience `"Download"` (prefixed by `audiencePrefix`). Each token SHALL include `iat` (issued-at) set to now, the first configured issuer from the comma-separated `issuer` list, expiration if `expirationTime > 0`, all claims from the `Token` object mapped by their `jwtClaimName`, and a random UUID as `sub` if no subject is provided in the claims. The JWT SHALL be signed using the private key from the `KeyProvider` reference and the configured algorithm.

#### Scenario: Upload token created with expiration
- **GIVEN** `TokenServiceConfig` with `algorithm = "HS512"`, `issuer = "filestore"`, `audiencePrefix = "judo-"`, `expirationTime = 30`, and a `KeyProvider` is available
- **WHEN** `createUploadToken(token)` is called with a `Token<UploadClaim>` containing `MAX_FILE_SIZE = 1048576` and `CONTEXT = "avatar"`
- **THEN** the returned JWT string, when decoded, contains `aud = "judo-Upload"`, `iss = "filestore"`, `maxFileSize = 1048576`, `ctx = "avatar"`, an `exp` claim set 30 minutes in the future, an `iat` claim, and a `sub` claim

#### Scenario: Download token created without expiration
- **GIVEN** `TokenServiceConfig` with `algorithm = "HS512"`, `issuer = null`, `audiencePrefix = null`, `expirationTime = 0`
- **WHEN** `createDownloadToken(token)` is called with a `Token<DownloadClaim>` containing `FILE_ID = "abc-123"` and `FILE_NAME = "report.pdf"`
- **THEN** the returned JWT string contains `aud = "Download"`, no `iss` claim, no `exp` claim, `sub = "abc-123"`, and `fileName = "report.pdf"`

### Requirement: Token Validation

The `DefaultTokenValidator` SHALL parse JWT strings and return typed `Token` objects. The `parseUploadToken(String)` method SHALL validate the JWT against audience `"Upload"` (with `audiencePrefix`) and return a `Token<UploadClaim>`. The `parseDownloadToken(String)` method SHALL validate against audience `"Download"` (with `audiencePrefix`) and return a `Token<DownloadClaim>`. Validation SHALL enforce the configured algorithm constraint via `AlgorithmConstraints.ConstraintType.PERMIT`, require a subject claim, verify the signature using the public key from `KeyProvider`, enforce expiration if `expirationTime > 0`, and validate issuer(s) if configured (comma-separated). If the token string is null, the method SHALL return null. If the token string is empty or whitespace-only, an empty claims map SHALL be returned. If validation fails, an `InvalidTokenException` SHALL be thrown.

#### Scenario: Valid upload token parsed
- **GIVEN** a JWT string signed with the correct key, algorithm `"HS512"`, audience `"Upload"`, issuer `"filestore"`, containing claims `maxFileSize = 5242880` and `mimeTypeList = "image/png,image/jpeg"`
- **WHEN** `parseUploadToken(tokenString)` is called
- **THEN** the returned `Token<UploadClaim>` contains `UploadClaim.MAX_FILE_SIZE` mapped to `5242880` and `UploadClaim.FILE_MIME_TYPE_LIST` mapped to `"image/png,image/jpeg"`

#### Scenario: Null token input
- **GIVEN** a null token string
- **WHEN** `parseUploadToken(null)` is called
- **THEN** the method returns `null`

#### Scenario: Invalid token rejected
- **GIVEN** a JWT string signed with a different key than the one held by `KeyProvider`
- **WHEN** `parseDownloadToken(tokenString)` is called
- **THEN** an `InvalidTokenException` is thrown

### Requirement: OSGi Configuration Requirement

All three components (`DefaultKeyProvider`, `DefaultTokenIssuer`, `DefaultTokenValidator`) SHALL use `configurationPolicy = ConfigurationPolicy.REQUIRE`, meaning they will not activate without a valid OSGi configuration being supplied. The `@Designate` annotation SHALL bind `DefaultKeyProvider` to `KeyProiderConfig` and both `DefaultTokenIssuer` and `DefaultTokenValidator` to `TokenServiceConfig`.

#### Scenario: Component not activated without configuration
- **GIVEN** no OSGi configuration is provided for `KeyProiderConfig`
- **WHEN** the OSGi container attempts to activate `DefaultKeyProvider`
- **THEN** the component remains in the `UNSATISFIED` state and is not registered as a `KeyProvider` service
