# S3 FileStore Specification

## Purpose

Provides an Amazon S3-backed implementation of the `FileStoreService` interface, storing file content as S3 objects and metadata as object tags or companion metadata entries, enabling cloud-native file storage with the same API contract as the filesystem and RDBMS implementations.

**Note:** This module does not currently exist in the repository. This specification defines the expected behavior for a future S3 implementation based on the `FileStoreService` contract and the patterns established by the existing `FileSystemFileStoreService` and `RdbmsFileStoreService` implementations.

## Architecture

### Key Classes and Relationships (Proposed)

- **`S3FileStoreService`** -- SHALL implement `FileStoreService` (`hu.blackbelt.osgi.filestore.api`). Should be declared as `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)` following the same OSGi Declarative Services pattern as the sibling implementations.
- **`FileStoreService`** (`hu.blackbelt.osgi.filestore.api`) -- The shared API interface defining `put(InputStream, String, String)`, `exists(String)`, `get(String)`, `getMimeType(String)`, `getFileName(String)`, `getSize(String)`, `getCreateTime(String)`, `getAccessUrl(String)`, and `getProtocol()`.
- **`FilenameUtils`** (`hu.blackbelt.osgi.filestore.api`) -- SHALL be used for file name sanitisation via `makeValidFilename(String)`, consistent with sibling implementations.
- **`FileStoreUrlStreamHandler`** (`hu.blackbelt.osgi.filestore.urlhandler`) -- SHALL be registered as `URLStreamHandlerService` on activation, consistent with sibling implementations.
- **`MimeTypeService`** (Apache Sling) -- SHALL be `@Reference`-injected for MIME type detection and extension lookup.

### Configuration (Proposed `Config` inner @interface)

| Attribute          | Description                              | Default       |
|--------------------|------------------------------------------|---------------|
| `protocol()`       | Protocol for URL stream handler          | (required)    |
| `bucket()`         | S3 bucket name                           | (required)    |
| `region()`         | AWS region                               | (required)    |
| `endpoint()`       | Custom S3 endpoint (for S3-compatible)   | (optional)    |
| `prefix()`         | Key prefix for all stored objects        | `""`          |

### Storage Layout (Proposed)

Files SHALL be stored as S3 objects with key `<prefix><fileId>`. Object metadata (S3 user metadata or object tags) SHALL include: `filename`, `mime-type`, `create-date`, `size`. The S3 object's `Content-Type` SHALL be set to the MIME type.

### Caching (Proposed)

A `LoadingCache<String, Map<String, String>>` with `maximumSize = 10000` and `expireAfterWrite = 10 minutes` SHALL cache object metadata, consistent with sibling implementations.

## Requirements

### Requirement: File Storage with UUID Identification

The service SHALL generate a UUID-based `fileId`, upload the file content to S3 as an object with key `<prefix><fileId>`, and store metadata (filename, MIME type, create time, size) as S3 object metadata.

#### Scenario: Store a file with explicit fileName and mimeType
- **GIVEN** `S3FileStoreService` is activated with `bucket = "my-bucket"`, `prefix = "files/"`, and `protocol = "judos3"`
- **WHEN** `put(inputStream, "report.pdf", "application/pdf")` is called
- **THEN** a UUID-based `fileId` is returned, the file content is uploaded to `s3://my-bucket/files/<fileId>` with `Content-Type: application/pdf`, and user metadata includes `filename=report.pdf`, `create-date=<epoch-millis>`, `size=<byte-count>`

#### Scenario: Store a file with null fileName and known mimeType
- **GIVEN** `MimeTypeService.getExtension("image/png")` returns `"png"`
- **WHEN** `put(inputStream, null, "image/png")` is called
- **THEN** the metadata `filename` is set to `<fileId>.png` and `Content-Type` is `image/png`

#### Scenario: Store a file with both fileName and mimeType null
- **GIVEN** `MimeTypeService.getMimeType("<fileId>.bin")` returns `null`
- **WHEN** `put(inputStream, null, null)` is called
- **THEN** the metadata `filename` is `<fileId>.bin` and MIME type defaults to `"application/octet-stream"`

### Requirement: File Name Sanitisation

The service SHALL sanitise provided file names through `FilenameUtils.makeValidFilename(String)` before storing in metadata, consistent with sibling implementations.

#### Scenario: File name containing special characters
- **GIVEN** the caller provides `fileName = "my$file (copy)[1].txt"`
- **WHEN** `put(inputStream, "my$file (copy)[1].txt", "text/plain")` is called
- **THEN** the stored metadata `filename` has special characters removed per `FilenameUtils.makeValidFilename` rules

### Requirement: File Existence Check

The service SHALL return `true` from `exists(String)` if the S3 object with key `<prefix><fileId>` exists in the configured bucket.

#### Scenario: Check existence of a stored file
- **GIVEN** an object with key `files/abc123` exists in `my-bucket`
- **WHEN** `exists("abc123")` is called
- **THEN** the method returns `true`

#### Scenario: Check existence of a non-existent file
- **GIVEN** no object with key `files/nonexistent` exists in `my-bucket`
- **WHEN** `exists("nonexistent")` is called
- **THEN** the method returns `false`

### Requirement: File Content Retrieval

The service SHALL retrieve the S3 object content as an `InputStream` when `get(String)` is called with a valid fileId.

#### Scenario: Retrieve a previously stored file
- **GIVEN** a file with content `"Hello World"` was stored with `fileId = "abc123"`
- **WHEN** `get("abc123")` is called
- **THEN** the returned `InputStream` yields the bytes of `"Hello World"`

### Requirement: Cached Metadata Access

The service SHALL serve `getMimeType(String)`, `getFileName(String)`, `getSize(String)`, and `getCreateTime(String)` from a Guava `LoadingCache` (max 10000 entries, 10-minute expiry). On cache miss, metadata SHALL be fetched from the S3 object's user metadata.

#### Scenario: Retrieve MIME type from cache
- **GIVEN** a file was stored with MIME type `"application/pdf"` and the cache is populated
- **WHEN** `getMimeType(fileId)` is called twice within 10 minutes
- **THEN** both calls return `"application/pdf"` and S3 is queried at most once

#### Scenario: Non-existent fileId throws exception
- **GIVEN** no S3 object exists for `fileId = "nonexistent"`
- **WHEN** `getFileName("nonexistent")` is called
- **THEN** an appropriate exception is thrown indicating the file was not found

### Requirement: Protocol-Prefixed ID Stripping

The service SHALL accept fileIds prefixed with `<protocol>:` and strip the prefix before resolving, consistent with sibling implementations.

#### Scenario: Access file using protocol-prefixed ID
- **GIVEN** a file was stored with `fileId = "abc123"` and `protocol = "judos3"`
- **WHEN** `get("judos3:abc123")` is called
- **THEN** the S3 object for key `<prefix>abc123` is retrieved

### Requirement: Access URL Generation

The service SHALL return a `URL` in the format `<protocol>:<fileId>-<fileName>` from `getAccessUrl(String)`, consistent with sibling implementations.

#### Scenario: Generate access URL
- **GIVEN** a file with `fileId = "abc123"`, `fileName = "report.pdf"`, and `protocol = "judos3"`
- **WHEN** `getAccessUrl("abc123")` is called
- **THEN** the returned URL is `"judos3:abc123-report.pdf"`

### Requirement: OSGi Lifecycle Management

The service SHALL register a `URLStreamHandlerService` on activation and unregister it on deactivation, consistent with sibling implementations.

#### Scenario: Component activation
- **GIVEN** the OSGi container provides a `BundleContext` and a valid `Config`
- **WHEN** the `activate` method is called
- **THEN** a `FileStoreUrlStreamHandler` is registered as a `URLStreamHandlerService` with property `url.handler.protocol` set to the configured protocol, and the S3 client is initialised with the configured bucket, region, and optional endpoint

#### Scenario: Component deactivation
- **GIVEN** the component was previously activated
- **WHEN** `deactivate()` is called
- **THEN** the `URLStreamHandlerService` registration is unregistered, the S3 client is closed, and references are set to `null`
