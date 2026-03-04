# Filestore URL Handler Specification

## Purpose

Provides an OSGi URL stream handler that resolves custom filestore protocol URLs (in the format `<protocol>:<uuid>-<filename>`) to file content stored in the `FileStoreService`, enabling transparent access to filestore entries through the standard `java.net.URL` API.

## Architecture

- **`FileStoreUrlStreamHandler`** -- Extends `org.osgi.service.url.AbstractURLStreamHandlerService` and implements `URLStreamHandlerService`. Receives a `FileStoreService` reference via its constructor. Overrides `openConnection(URL)` to parse the UUID from the URL string and return a `FileStoreUrlConnection`. The UUID is extracted by taking the substring between the protocol prefix (`fileStoreService.getProtocol() + ":"`) and the first hyphen character (`"-"`).
- **`FileStoreUrlConnection`** -- Extends `java.net.URLConnection`. Holds references to the `FileStoreService` and the parsed `uuid` string. Provides:
  - `connect()` -- No-op implementation (connection is implicit).
  - `getInputStream()` -- Returns `fileStoreService.get(uuid)`, providing the file content as an `InputStream`.
  - `getContentLength()` -- Calls `fileStoreService.getSize(uuid)` (though note: the return value is discarded and `super.getContentLength()` is returned, which is a known implementation quirk).
  - `getContentType()` -- Returns `fileStoreService.getMimeType(uuid)`.
  - `getDate()` -- Returns `fileStoreService.getCreateTime(uuid).getTime()` as a `long` epoch timestamp.

### Dependency Graph

```
FileStoreUrlStreamHandler ---constructor---> FileStoreService
        |
        +---creates---> FileStoreUrlConnection ---uses---> FileStoreService
```

### URL Format

```
<protocol>:<uuid>-<filename>
```

Where `<protocol>` is the value returned by `FileStoreService.getProtocol()`, `<uuid>` is the file identifier in the store, and `<filename>` is a human-readable name. Only the `<uuid>` portion is used for filestore lookups.

## Requirements

### Requirement: URL Connection Opening

The `FileStoreUrlStreamHandler.openConnection(URL)` method SHALL parse the UUID from the given URL and return a `FileStoreUrlConnection`. The UUID SHALL be extracted by taking the substring of `url.toString()` starting after `fileStoreService.getProtocol() + ":"` and ending before the first `"-"` character. The returned `FileStoreUrlConnection` SHALL hold references to the original URL, the `FileStoreService`, and the extracted UUID.

#### Scenario: Open connection from a valid filestore URL
- **GIVEN** `FileStoreService.getProtocol()` returns `"filestore"` and the URL is `"filestore:550e8400e29b41d4-document.pdf"`
- **WHEN** `openConnection(url)` is called
- **THEN** a `FileStoreUrlConnection` is returned with `uuid = "550e8400e29b41d4"` and the URL set to the original URL

#### Scenario: UUID extraction with standard UUID format
- **GIVEN** `FileStoreService.getProtocol()` returns `"fs"` and the URL is `"fs:a1b2c3d4-report.xlsx"`
- **WHEN** `openConnection(url)` is called
- **THEN** the extracted UUID is `"a1b2c3d4"` (everything between `"fs:"` and the first `"-"`)

### Requirement: File Content Streaming

The `FileStoreUrlConnection.getInputStream()` method SHALL return the `InputStream` obtained by calling `fileStoreService.get(uuid)` with the UUID that was parsed during connection creation. This enables callers to read the file content through the standard `URL.openStream()` or `URLConnection.getInputStream()` API.

#### Scenario: Read file content through URL connection
- **GIVEN** a `FileStoreUrlConnection` with `uuid = "abc123"` and the filestore contains a file with ID `"abc123"` and content bytes `[0x48, 0x65, 0x6C, 0x6C, 0x6F]`
- **WHEN** `getInputStream()` is called
- **THEN** the returned `InputStream` yields the bytes `[0x48, 0x65, 0x6C, 0x6C, 0x6F]` (i.e., `"Hello"`)

#### Scenario: File not found in store
- **GIVEN** a `FileStoreUrlConnection` with `uuid = "nonexistent"` and the filestore has no file with that ID
- **WHEN** `getInputStream()` is called
- **THEN** the `fileStoreService.get("nonexistent")` call determines the behavior (typically throws `IOException`)

### Requirement: Content Metadata Retrieval

The `FileStoreUrlConnection` SHALL expose file metadata through standard `URLConnection` methods:
- `getContentType()` SHALL return the MIME type of the file by calling `fileStoreService.getMimeType(uuid)`.
- `getDate()` SHALL return the file creation timestamp as a `long` epoch value (milliseconds since 1970-01-01) by calling `fileStoreService.getCreateTime(uuid).getTime()`.

#### Scenario: Retrieve content type
- **GIVEN** a `FileStoreUrlConnection` with `uuid = "doc456"` and the filestore reports MIME type `"application/pdf"` for that ID
- **WHEN** `getContentType()` is called
- **THEN** the return value is `"application/pdf"`

#### Scenario: Retrieve file creation date
- **GIVEN** a `FileStoreUrlConnection` with `uuid = "doc456"` and the filestore reports creation time of `2024-01-15T10:30:00Z`
- **WHEN** `getDate()` is called
- **THEN** the return value is the epoch millisecond value corresponding to `2024-01-15T10:30:00Z` (i.e., `1705312200000`)

### Requirement: No-Op Connection

The `FileStoreUrlConnection.connect()` method SHALL be a no-op. The connection does not require an explicit connect step because all data access is delegated to `FileStoreService` method calls on demand.

#### Scenario: Connect is no-op
- **GIVEN** a `FileStoreUrlConnection` instance
- **WHEN** `connect()` is called
- **THEN** no exception is thrown and no state changes occur

### Requirement: Protocol-Based URL Scheme Registration

The `FileStoreUrlStreamHandler` SHALL be usable as an OSGi URL stream handler service (implementing `URLStreamHandlerService`) that handles the protocol scheme defined by `FileStoreService.getProtocol()`. When registered in the OSGi service registry with the `url.handler.protocol` property set to the filestore protocol, the OSGi framework SHALL route URL resolution for that protocol through this handler.

#### Scenario: URL resolution routed through handler
- **GIVEN** `FileStoreUrlStreamHandler` is registered as a `URLStreamHandlerService` with property `url.handler.protocol = "filestore"`
- **WHEN** code calls `new URL("filestore:abc123-myfile.txt").openConnection()`
- **THEN** the call is delegated to `FileStoreUrlStreamHandler.openConnection(url)` and returns a `FileStoreUrlConnection`
