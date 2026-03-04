# FileStore API Specification

## Purpose

Defines the contract for storing, retrieving, and querying metadata of binary files identified by opaque string IDs. All implementations must support streaming I/O and provide protocol-specific access URLs.

## Architecture

- **`hu.blackbelt.osgi.filestore.api.FileStoreService`** -- Core service interface. Every file store backend (filesystem, RDBMS, etc.) implements this interface to provide a uniform storage abstraction.
- **`hu.blackbelt.osgi.filestore.api.FilenameUtils`** -- Utility class with a single static method `makeValidFilename(String)` that sanitizes file names by removing reserved OS names (AUX, CON, NUL, PRN, COM1-9, LPT1-9), stripping illegal characters (`$()+=[];#@~,&'`), collapsing whitespace and dots, and truncating to 128 characters.
- Methods that accept a `fileId` operate on an opaque identifier returned by `put()`.
- `put()` accepts an `InputStream`, a file name, and a MIME type; returns the generated `fileId`.
- `get()` returns an `InputStream` for the stored binary content.
- `exists()` is the only query method that does not throw `IOException`.
- `getAccessUrl()` returns a `java.net.URL` for direct access to the file.
- `getProtocol()` returns the protocol scheme used by the implementation (e.g. `"file"`, `"jdbc"`).

## Requirements

### Requirement: File storage and ID generation

The service SHALL accept binary content via `put(InputStream data, String fileName, String mimeType)` and return a non-null string identifier that can be used for all subsequent operations on that file.

#### Scenario: Store a new file and receive an ID

- **GIVEN** a `FileStoreService` implementation is available
- **WHEN** `put()` is called with a valid `InputStream`, fileName `"report.pdf"`, and mimeType `"application/pdf"`
- **THEN** a non-null, non-empty `String` fileId is returned and the file content is persisted

#### Scenario: Store a file with an empty input stream

- **GIVEN** a `FileStoreService` implementation is available
- **WHEN** `put()` is called with an empty `InputStream`, fileName `"empty.txt"`, and mimeType `"text/plain"`
- **THEN** a valid fileId is returned (zero-length files are permitted)

### Requirement: File retrieval

The service SHALL return the binary content of a previously stored file as an `InputStream` when `get(String fileId)` is called with a valid fileId.

#### Scenario: Retrieve a previously stored file

- **GIVEN** a file was stored via `put()` and its fileId is known
- **WHEN** `get(fileId)` is called
- **THEN** the returned `InputStream` yields the same bytes that were originally stored

#### Scenario: Retrieve a non-existent file

- **GIVEN** no file with ID `"nonexistent-id"` exists in the store
- **WHEN** `get("nonexistent-id")` is called
- **THEN** an `IOException` is thrown

### Requirement: Existence check

The service SHALL return `true` from `exists(String fileId)` if and only if the file identified by `fileId` has been stored and is available for retrieval. This method does not throw `IOException`.

#### Scenario: Check existence of a stored file

- **GIVEN** a file was stored via `put()` with returned fileId `"abc123"`
- **WHEN** `exists("abc123")` is called
- **THEN** the method returns `true`

#### Scenario: Check existence of an unknown file

- **GIVEN** no file with ID `"unknown"` has been stored
- **WHEN** `exists("unknown")` is called
- **THEN** the method returns `false`

### Requirement: MIME type retrieval

The service SHALL return the MIME type string that was provided during `put()` when `getMimeType(String fileId)` is called.

#### Scenario: Retrieve MIME type of a stored PDF

- **GIVEN** a file was stored with mimeType `"application/pdf"` and fileId `"pdf-001"`
- **WHEN** `getMimeType("pdf-001")` is called
- **THEN** the string `"application/pdf"` is returned

### Requirement: File name retrieval

The service SHALL return the original file name that was provided during `put()` when `getFileName(String fileId)` is called.

#### Scenario: Retrieve file name of a stored file

- **GIVEN** a file was stored with fileName `"report.pdf"` and fileId `"pdf-001"`
- **WHEN** `getFileName("pdf-001")` is called
- **THEN** the string `"report.pdf"` is returned

### Requirement: File size retrieval

The service SHALL return the size in bytes of the stored file when `getSize(String fileId)` is called. The return type is `long`.

#### Scenario: Retrieve size of a stored file

- **GIVEN** a 2048-byte file was stored with fileId `"file-2k"`
- **WHEN** `getSize("file-2k")` is called
- **THEN** the value `2048L` is returned

### Requirement: Creation time retrieval

The service SHALL return a `java.util.Date` representing the creation timestamp when `getCreateTime(String fileId)` is called.

#### Scenario: Retrieve creation time after storage

- **GIVEN** a file was stored at time T with fileId `"timestamped"`
- **WHEN** `getCreateTime("timestamped")` is called
- **THEN** the returned `Date` is equal to or very close to T

### Requirement: Access URL retrieval

The service SHALL return a `java.net.URL` providing direct access to the stored file when `getAccessUrl(String fileId)` is called. The URL scheme should match the value returned by `getProtocol()`.

#### Scenario: Retrieve access URL of a stored file

- **GIVEN** a file was stored with fileId `"url-file"` in an implementation whose `getProtocol()` returns `"file"`
- **WHEN** `getAccessUrl("url-file")` is called
- **THEN** the returned `URL` is non-null and its protocol is `"file"`

### Requirement: Protocol identification

The service SHALL return a non-null string identifying the storage protocol via `getProtocol()`. This allows callers to distinguish between storage backends.

#### Scenario: Query protocol of a filesystem-based store

- **GIVEN** a filesystem-backed `FileStoreService` implementation
- **WHEN** `getProtocol()` is called
- **THEN** a non-null protocol string (e.g. `"file"`) is returned

### Requirement: Filename sanitization

`FilenameUtils.makeValidFilename(String)` SHALL sanitize file names by removing reserved Windows device names, stripping special characters (`$()+=[];#@~,&'`), collapsing multiple dots and whitespace, trimming, and truncating results longer than 128 characters.

#### Scenario: Sanitize a filename containing reserved name

- **GIVEN** the input string `"CON"`
- **WHEN** `FilenameUtils.makeValidFilename("CON")` is called
- **THEN** the result is `"reserved"` (the reserved name is replaced)

#### Scenario: Truncate an excessively long filename

- **GIVEN** a filename string of 200 characters
- **WHEN** `FilenameUtils.makeValidFilename(longName)` is called
- **THEN** the result is at most 128 characters long
