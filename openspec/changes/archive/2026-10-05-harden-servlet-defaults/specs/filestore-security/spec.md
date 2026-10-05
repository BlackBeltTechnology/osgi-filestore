## MODIFIED Requirements

### Requirement: Token Validation

The `DefaultTokenValidator` SHALL parse JWT strings and return typed `Token` objects. The `parseUploadToken(String)` method SHALL validate the JWT against audience `"Upload"` (with `audiencePrefix`) and return a `Token<UploadClaim>`. The `parseDownloadToken(String)` method SHALL validate against audience `"Download"` (with `audiencePrefix`) and return a `Token<DownloadClaim>`. Validation SHALL enforce the configured algorithm constraint via `AlgorithmConstraints.ConstraintType.PERMIT`, require a subject claim, verify the signature using the public key from `KeyProvider`, enforce expiration if `expirationTime > 0`, and validate issuer(s) if configured (comma-separated). Time-based checks (`exp`, `nbf`) SHALL tolerate a clock difference of `allowedClockSkew` seconds (default `60`). If the token string is null, the method SHALL return null. If the token string is empty or whitespace-only, an empty claims map SHALL be returned. If validation fails, an `InvalidTokenException` SHALL be thrown.

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

#### Scenario: Expired token within clock skew accepted
- **GIVEN** a validator with `expirationTime = 30` and `allowedClockSkew = 60`
- **AND** a correctly signed download token whose `exp` lies 30 seconds in the past
- **WHEN** `parseDownloadToken(tokenString)` is called
- **THEN** the token is accepted

#### Scenario: Expired token beyond clock skew rejected
- **GIVEN** a validator with `expirationTime = 30` and `allowedClockSkew = 60`
- **AND** a correctly signed download token whose `exp` lies 120 seconds in the past
- **WHEN** `parseDownloadToken(tokenString)` is called
- **THEN** an `InvalidTokenException` is thrown

#### Scenario: Token without expiration rejected when expiration is enforced
- **GIVEN** a validator with `expirationTime = 1440`
- **AND** a correctly signed download token with no `exp` claim, for example one issued before an upgrade
- **WHEN** `parseDownloadToken(tokenString)` is called
- **THEN** an `InvalidTokenException` is thrown

## ADDED Requirements

### Requirement: Default Token Expiration

When `expirationTime` is not configured, `TokenServiceConfig` SHALL default it to `1440` minutes (24 hours) for both `DefaultTokenIssuer` and `DefaultTokenValidator`. An explicitly configured `expirationTime = 0` SHALL keep its meaning of "tokens do not expire". `allowedClockSkew` SHALL default to `60` seconds when not configured.

#### Scenario: Expiration not configured
- **GIVEN** a `TokenServiceConfig` with no `expirationTime` property
- **WHEN** `createDownloadToken(token)` is called
- **THEN** the JWT contains an `exp` claim 1440 minutes after its `iat` claim

#### Scenario: Expiration explicitly disabled
- **GIVEN** a `TokenServiceConfig` with `expirationTime = 0`
- **WHEN** `createDownloadToken(token)` is called
- **THEN** the JWT contains no `exp` claim
- **AND** a validator with the same configuration accepts it

### Requirement: Non-Expiring Token Warning

On activation with `expirationTime = 0`, `DefaultTokenIssuer` SHALL log at WARN level, once per activation, that issued tokens never expire. The warning SHALL NOT change token issuance.

#### Scenario: Expiration disabled
- **GIVEN** a `TokenServiceConfig` with `expirationTime = 0`
- **WHEN** `DefaultTokenIssuer` is activated
- **THEN** one WARN entry is logged naming `expirationTime`

#### Scenario: Expiration enabled is silent
- **GIVEN** a `TokenServiceConfig` with `expirationTime = 1440`
- **WHEN** `DefaultTokenIssuer` is activated
- **THEN** no WARN entry about token expiration is logged
