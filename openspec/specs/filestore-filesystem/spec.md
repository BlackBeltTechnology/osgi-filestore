# Filesystem FileStore Specification

## Purpose

Provides a local filesystem-backed implementation of the `FileStoreService` interface, storing file content and metadata in a nested directory structure derived from the file identifier, with Guava-based caching for metadata lookups.

## Architecture

### Key Classes and Relationships

- **`FileSystemFileStoreService`** (`hu.blackbelt.osgi.filestore.filesystem`) -- Implements `FileStoreService`. Declared as an OSGi Declarative Services `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)` with `@Designate(ocd = FileSystemFileStoreService.Config.class)`.
- **`FileStoreService`** (`hu.blackbelt.osgi.filestore.api`) -- The API interface defining `put(InputStream, String, String)`, `exists(String)`, `get(String)`, `getMimeType(String)`, `getFileName(String)`, `getSize(String)`, `getCreateTime(String)`, `getAccessUrl(String)`, and `getProtocol()`.
- **`FilenameUtils`** (`hu.blackbelt.osgi.filestore.api`) -- Utility that sanitises file names via `makeValidFilename(String)`, stripping reserved Windows names, special characters, and truncating to 128 characters.
- **`FileStoreUrlStreamHandler`** (`hu.blackbelt.osgi.filestore.urlhandler`) -- Registered as an OSGi `URLStreamHandlerService` on activation, enabling `protocol:fileId-fileName` URL resolution.
- **`MimeTypeService`** (Apache Sling) -- `@Reference`-injected; used for MIME type detection and file-extension lookup.

### Configuration (`Config` inner @interface)

| Attribute                    | Description                          | Default                              |
|------------------------------|--------------------------------------|--------------------------------------|
| `protocol()`                 | Protocol for URL stream handler      | (required)                           |
| `fileSystemStoreDirectory()` | Root directory for file storage      | `System.getProperty("user.home") + "/file-store"` |

### Storage Layout

Files are stored under `<rootDir>/<2-char>/<2-char>/.../<remainder>/` where the path is derived by splitting the 32-hex-char UUID (hyphens removed) into 2-character segments joined by the filesystem separator. Each directory contains:
- The data file, named by the sanitised `fileName`.
- A `file.properties` sidecar containing keys: `file-name`, `mime-type`, `create-date` (epoch millis), `size` (bytes).

### Caching

A `LoadingCache<String, java.util.Properties>` (`propertiesLoadingCache`) with `maximumSize = 10000` and `expireAfterWrite = 10 minutes` caches parsed `.properties` files, loaded by `idToProperties(String)`.

## Requirements

### Requirement: File Storage with UUID Identification

The service SHALL generate a 32-character hex UUID (hyphens removed) for each stored file, create the nested 2-character directory structure, persist the file content, and write a `file.properties` sidecar with metadata.

#### Scenario: Store a file with explicit fileName and mimeType
- **GIVEN** `FileSystemFileStoreService` is activated with `fileSystemStoreDirectory = "/data/store"` and `protocol = "judostore"`
- **WHEN** `put(inputStream, "report.pdf", "application/pdf")` is called
- **THEN** a 32-hex-char `fileId` is returned, the file content is written to `<rootDir>/<nested-path>/report.pdf`, and a `file.properties` sidecar is created in the same directory containing `file-name=report.pdf`, `mime-type=application/pdf`, `create-date=<epoch-millis>`, and `size=<byte-count>`

#### Scenario: Store a file with null fileName but known mimeType
- **GIVEN** `FileSystemFileStoreService` is activated and `MimeTypeService.getExtension("image/png")` returns `"png"`
- **WHEN** `put(inputStream, null, "image/png")` is called
- **THEN** the generated file is named `<fileId>.png` and `mime-type` in the sidecar is `image/png`

#### Scenario: Store a file with both fileName and mimeType null
- **GIVEN** `FileSystemFileStoreService` is activated and `MimeTypeService.getMimeType("<fileId>.bin")` returns `null`
- **WHEN** `put(inputStream, null, null)` is called
- **THEN** the generated file is named `<fileId>.bin` and `mime-type` in the sidecar is `"application/octet-stream"`

### Requirement: File Name Sanitisation

The service SHALL sanitise provided file names through `FilenameUtils.makeValidFilename(String)` before persisting.

#### Scenario: File name containing special characters
- **GIVEN** the caller provides `fileName = "my$file (copy)[1].txt"`
- **WHEN** `put(inputStream, "my$file (copy)[1].txt", "text/plain")` is called
- **THEN** the stored file name has `$`, `(`, `)`, `[`, `]` characters removed, resulting in `"myfile copy1.txt"`

### Requirement: File Existence Check

The service SHALL return `true` from `exists(String)` if and only if the data file exists on disk for the given fileId.

#### Scenario: Check existence of a stored file
- **GIVEN** a file was previously stored and assigned `fileId = "abc123..."`
- **WHEN** `exists("abc123...")` is called
- **THEN** the method returns `true`

#### Scenario: Check existence of a non-existent file
- **GIVEN** no file has been stored with `fileId = "nonexistent"`
- **WHEN** `exists("nonexistent")` is called
- **THEN** the method returns `false`

### Requirement: File Retrieval

The service SHALL return an `InputStream` of the file content when `get(String)` is called with a valid fileId.

#### Scenario: Retrieve a previously stored file
- **GIVEN** a file with content `"Hello World"` was stored with `fileId = "abc123..."`
- **WHEN** `get("abc123...")` is called
- **THEN** the returned `InputStream` yields the bytes of `"Hello World"`

### Requirement: Cached Metadata Access

The service SHALL serve `getMimeType(String)`, `getFileName(String)`, `getSize(String)`, and `getCreateTime(String)` from the Guava `LoadingCache`, loading from the `file.properties` sidecar on cache miss.

#### Scenario: Retrieve MIME type from cache
- **GIVEN** a file was stored with `mime-type = "application/pdf"` and the cache entry has not expired
- **WHEN** `getMimeType(fileId)` is called twice in succession
- **THEN** both calls return `"application/pdf"` and the `file.properties` file is read from disk at most once

#### Scenario: Cache expiration triggers reload
- **GIVEN** the cache entry for `fileId` has expired (older than 10 minutes)
- **WHEN** `getFileName(fileId)` is called
- **THEN** the `file.properties` file is re-read from disk and the result is cached again

### Requirement: Null FileId Rejection

The service SHALL throw `NullPointerException` with message `"fileId cannot be null"` when any accessor method (`exists`, `get`, `getMimeType`, `getFileName`, `getSize`, `getCreateTime`, `getAccessUrl`) is called with a `null` fileId.

#### Scenario: Null fileId on get
- **GIVEN** the service is activated
- **WHEN** `get(null)` is called
- **THEN** a `NullPointerException` is thrown with message `"fileId cannot be null"`

### Requirement: Protocol-Prefixed ID Stripping

The service SHALL accept fileIds prefixed with `<protocol>:` (e.g., `"judostore:abc123..."`) and strip the prefix before resolving, via the private `getStrippedId(String)` method.

#### Scenario: Access file using protocol-prefixed ID
- **GIVEN** a file was stored with `fileId = "abc123..."` and `protocol = "judostore"`
- **WHEN** `get("judostore:abc123...")` is called
- **THEN** the file content for `"abc123..."` is returned

### Requirement: Access URL Generation

The service SHALL return a `URL` in the format `<protocol>:<fileId>-<fileName>` from `getAccessUrl(String)`.

#### Scenario: Generate access URL
- **GIVEN** a file was stored with `fileId = "abc123..."`, `fileName = "report.pdf"`, and `protocol = "judostore"`
- **WHEN** `getAccessUrl("abc123...")` is called
- **THEN** the returned URL is `"judostore:abc123...-report.pdf"`

### Requirement: OSGi Lifecycle Management

The service SHALL register a `URLStreamHandlerService` on activation and unregister it on deactivation.

#### Scenario: Component activation
- **GIVEN** the OSGi container provides a `BundleContext` and a valid `Config`
- **WHEN** the `activate(BundleContext, Config)` method is called
- **THEN** a `FileStoreUrlStreamHandler` is registered as a `URLStreamHandlerService` with property `url.handler.protocol` set to the configured protocol

#### Scenario: Component deactivation
- **GIVEN** the component was previously activated and the `URLStreamHandlerService` is registered
- **WHEN** `deactivate()` is called
- **THEN** the `URLStreamHandlerService` registration is unregistered and the reference is set to `null`
