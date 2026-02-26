# FileStore Security API Specification

## Purpose

Defines the security token model and interfaces for issuing and validating JWT-based tokens that authorize file upload and download operations against the file store.

## Architecture

- **`hu.blackbelt.osgi.filestore.security.api.Token<C extends Token.Claim>`** -- Generic, immutable token container built with Lombok `@Builder` and `@Getter`. Holds a `Map<C, Object>` of JWT claims (`@Singular jwtClaims`). Provides:
  - `Object get(C claim)` -- Retrieves and converts a single claim value using `claim.convert(value)`.
  - `Map<String, Object> getClaims()` -- Returns an unmodifiable map keyed by JWT claim names (via `Claim.getJwtClaimName()`), with values converted by each claim's `convert()` method.
- **`hu.blackbelt.osgi.filestore.security.api.Token.Claim`** -- Inner interface defining `String getJwtClaimName()` and a default `Object convert(Object value)` identity method. All claim enums implement this interface.
- **`hu.blackbelt.osgi.filestore.security.api.UploadClaim`** -- Enum implementing `Token.Claim` with `@Getter` and `@AllArgsConstructor`. Constants:
  - `FILE_MIME_TYPE_LIST` (JWT name: `"mimeTypeList"`) -- Allowed MIME types for upload.
  - `MAX_FILE_SIZE` (JWT name: `"maxFileSize"`) -- Maximum allowed file size; overrides `convert()` to parse `Double` then cast to `Long`.
  - `CONTEXT` (JWT name: `"ctx"`) -- Arbitrary context string.
  - Static `AUDIENCE = "Upload"`.
  - Static lookup method `getByJwtClaimName(String)` returns the matching enum constant or `null`.
- **`hu.blackbelt.osgi.filestore.security.api.DownloadClaim`** -- Enum implementing `Token.Claim` with `@Getter` and `@AllArgsConstructor`. Constants:
  - `FILE_ID` (JWT name: `"sub"`) -- The file identifier to download.
  - `FILE_NAME` (JWT name: `"fileName"`) -- Original file name.
  - `FILE_SIZE` (JWT name: `"fileSize"`) -- File size; overrides `convert()` to parse `Double` then cast to `Long`.
  - `FILE_MIME_TYPE` (JWT name: `"mimeType"`) -- MIME type of the file.
  - `DISPOSITION` (JWT name: `"disposition"`) -- Content-Disposition hint (inline/attachment).
  - `CONTEXT` (JWT name: `"ctx"`) -- Arbitrary context string.
  - Static `AUDIENCE = "Download"`.
  - Static lookup method `getByJwtClaimName(String)` returns the matching enum constant or `null`.
- **`hu.blackbelt.osgi.filestore.security.api.TokenIssuer`** -- Interface with:
  - `String createUploadToken(Token<UploadClaim> token)` -- Serializes an upload token to a JWT string.
  - `String createDownloadToken(Token<DownloadClaim> token)` -- Serializes a download token to a JWT string.
- **`hu.blackbelt.osgi.filestore.security.api.TokenValidator`** -- Interface with:
  - `Token<UploadClaim> parseUploadToken(String tokenString) throws InvalidTokenException` -- Parses and validates a JWT string into an upload token.
  - `Token<DownloadClaim> parseDownloadToken(String tokenString) throws InvalidTokenException` -- Parses and validates a JWT string into a download token.
- **`hu.blackbelt.osgi.filestore.security.api.exceptions.InvalidTokenException`** -- Checked exception wrapping a `Throwable` cause, thrown when token parsing or validation fails.

## Requirements

### Requirement: Token construction and claim access

`Token<C>` SHALL store claims in an immutable map and provide type-safe access via `get(C claim)`, delegating value conversion to the claim's `convert()` method.

#### Scenario: Build and read an upload token

- **GIVEN** a `Token<UploadClaim>` is built using `Token.<UploadClaim>builder().jwtClaim(UploadClaim.MAX_FILE_SIZE, 1048576).jwtClaim(UploadClaim.CONTEXT, "user-session").build()`
- **WHEN** `token.get(UploadClaim.MAX_FILE_SIZE)` is called
- **THEN** the value `1048576L` (a `Long`) is returned due to `MAX_FILE_SIZE.convert()` parsing logic

#### Scenario: Get claims map with JWT names

- **GIVEN** a `Token<DownloadClaim>` with `FILE_ID = "abc"` and `FILE_NAME = "report.pdf"`
- **WHEN** `token.getClaims()` is called
- **THEN** an unmodifiable `Map<String, Object>` is returned containing keys `"sub"` and `"fileName"` with values `"abc"` and `"report.pdf"` respectively

### Requirement: UploadClaim defines upload constraints

The `UploadClaim` enum SHALL define claims for `FILE_MIME_TYPE_LIST`, `MAX_FILE_SIZE`, and `CONTEXT` with JWT names `"mimeTypeList"`, `"maxFileSize"`, and `"ctx"` respectively. `MAX_FILE_SIZE.convert()` SHALL parse the value as a `Double` and return it as a `Long`.

#### Scenario: MAX_FILE_SIZE converts numeric value to Long

- **GIVEN** `UploadClaim.MAX_FILE_SIZE` and a value of `5242880.0` (a Double)
- **WHEN** `MAX_FILE_SIZE.convert(5242880.0)` is called
- **THEN** the result is `5242880L` (a `Long`)

#### Scenario: Lookup claim by JWT name

- **GIVEN** the JWT claim name `"maxFileSize"`
- **WHEN** `UploadClaim.getByJwtClaimName("maxFileSize")` is called
- **THEN** `UploadClaim.MAX_FILE_SIZE` is returned

#### Scenario: Lookup unknown JWT claim name returns null

- **GIVEN** the JWT claim name `"nonexistent"`
- **WHEN** `UploadClaim.getByJwtClaimName("nonexistent")` is called
- **THEN** `null` is returned

### Requirement: DownloadClaim defines download metadata

The `DownloadClaim` enum SHALL define claims for `FILE_ID` (`"sub"`), `FILE_NAME` (`"fileName"`), `FILE_SIZE` (`"fileSize"`), `FILE_MIME_TYPE` (`"mimeType"`), `DISPOSITION` (`"disposition"`), and `CONTEXT` (`"ctx"`). `FILE_SIZE.convert()` SHALL parse the value as a `Double` and return it as a `Long`.

#### Scenario: FILE_SIZE converts numeric value to Long

- **GIVEN** `DownloadClaim.FILE_SIZE` and a value of `1024.0`
- **WHEN** `FILE_SIZE.convert(1024.0)` is called
- **THEN** the result is `1024L`

#### Scenario: Lookup DownloadClaim by JWT name "sub"

- **GIVEN** the JWT claim name `"sub"`
- **WHEN** `DownloadClaim.getByJwtClaimName("sub")` is called
- **THEN** `DownloadClaim.FILE_ID` is returned

### Requirement: Token issuance

`TokenIssuer` SHALL serialize `Token<UploadClaim>` and `Token<DownloadClaim>` objects into JWT strings via `createUploadToken()` and `createDownloadToken()` respectively.

#### Scenario: Issue an upload token

- **GIVEN** a `TokenIssuer` implementation and a `Token<UploadClaim>` containing `FILE_MIME_TYPE_LIST = "image/png,image/jpeg"` and `MAX_FILE_SIZE = 10485760`
- **WHEN** `createUploadToken(token)` is called
- **THEN** a non-null JWT string is returned that encodes the claims with audience `"Upload"`

#### Scenario: Issue a download token

- **GIVEN** a `TokenIssuer` implementation and a `Token<DownloadClaim>` containing `FILE_ID = "file-001"`, `FILE_NAME = "photo.jpg"`, and `DISPOSITION = "attachment"`
- **WHEN** `createDownloadToken(token)` is called
- **THEN** a non-null JWT string is returned that encodes the claims with audience `"Download"`

### Requirement: Token validation

`TokenValidator` SHALL parse JWT strings back into `Token<UploadClaim>` or `Token<DownloadClaim>` objects via `parseUploadToken()` and `parseDownloadToken()`, throwing `InvalidTokenException` if the token is malformed, expired, or otherwise invalid.

#### Scenario: Parse a valid upload token

- **GIVEN** a valid JWT string previously created by `TokenIssuer.createUploadToken()`
- **WHEN** `parseUploadToken(tokenString)` is called
- **THEN** a `Token<UploadClaim>` is returned with the same claims as the original token

#### Scenario: Parse an invalid token string

- **GIVEN** a corrupted or tampered JWT string `"invalid.token.string"`
- **WHEN** `parseUploadToken("invalid.token.string")` is called
- **THEN** an `InvalidTokenException` is thrown wrapping the underlying parsing error

#### Scenario: Parse a valid download token

- **GIVEN** a valid JWT string previously created by `TokenIssuer.createDownloadToken()`
- **WHEN** `parseDownloadToken(tokenString)` is called
- **THEN** a `Token<DownloadClaim>` is returned containing the original claims including `FILE_ID`, `FILE_NAME`, and `DISPOSITION`

### Requirement: InvalidTokenException wraps cause

`InvalidTokenException` SHALL be a checked exception that wraps a `Throwable` cause, providing context about why token parsing failed.

#### Scenario: Exception wraps underlying cause

- **GIVEN** a `RuntimeException` with message `"JWT signature mismatch"`
- **WHEN** `new InvalidTokenException(cause)` is constructed
- **THEN** `getCause()` returns the original `RuntimeException`
