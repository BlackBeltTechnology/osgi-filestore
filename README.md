# OSGi Filestore

A pluggable, OSGi-based blob file store service with multiple independently deployable storage backends. Built on OSGi Declarative Services, it provides a unified API for storing and retrieving files across filesystem, RDBMS, and S3-compatible storage, with JWT-based security and HTTP upload/download servlets.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Module Structure](#module-structure)
- [Core API](#core-api)
- [Storage Backends](#storage-backends)
  - [Filesystem](#filesystem-backend)
  - [RDBMS](#rdbms-backend)
  - [S3](#s3-backend)
- [Security Layer](#security-layer)
- [HTTP Servlets](#http-servlets)
- [URL Protocol Handlers](#url-protocol-handlers)
- [MIME Type Resolution](#mime-type-resolution)
- [Karaf Deployment](#karaf-deployment)
- [Building](#building)
- [Testing](#testing)
- [Configuration Reference](#configuration-reference)
- [License](#license)

## Architecture Overview

```mermaid
graph TB
    subgraph "HTTP Layer"
        US[UploadServlet<br/>multipart upload]
        DS[DownloadServlet<br/>file download]
        CORS[CorsProcessor]
    end

    subgraph "Security Layer"
        TI[TokenIssuer]
        TV[TokenValidator]
        UC[UploadClaim]
        DC[DownloadClaim]
    end

    subgraph "Core API"
        FSS[FileStoreService<br/>interface]
        FU[FilenameUtils]
    end

    subgraph "Storage Backends"
        FS[FileSystemFileStoreService<br/>filesystem]
        RDB[RdbmsFileStoreService<br/>Spring JDBC + LiquiBase]
        S3[S3FileStoreService<br/>aws-lightweight-client]
    end

    subgraph "URL Handlers"
        UH[FileStoreUrlStreamHandler]
        UC2[FileStoreUrlConnection]
    end

    subgraph "MIME"
        MR[MimeTypeResolver]
        DMR[DefaultMimeTypeResolver]
        SMS[Apache Sling MimeTypeService]
    end

    US -->|validates tokens| TV
    DS -->|validates tokens| TV
    US -->|stores files| FSS
    DS -->|retrieves files| FSS
    US -.->|CORS| CORS
    DS -.->|CORS| CORS

    FSS --> FS
    FSS --> RDB
    FSS --> S3

    FS -->|registers protocol| UH
    RDB -->|registers protocol| UH
    S3 -->|registers protocol| UH

    FS -->|resolves MIME| SMS
    RDB -->|resolves MIME| SMS
    S3 -->|resolves MIME| SMS

    DMR --> SMS
    MR --> DMR
```

### Request Flow

```mermaid
sequenceDiagram
    participant Client
    participant UploadServlet
    participant TokenValidator
    participant FileStoreService
    participant StorageBackend
    participant TokenIssuer

    Note over Client,TokenIssuer: Upload Flow
    Client->>TokenIssuer: Request upload token
    TokenIssuer-->>Client: JWT (UploadClaim)
    Client->>UploadServlet: POST multipart + JWT
    UploadServlet->>TokenValidator: Validate token
    TokenValidator-->>UploadServlet: Claims (mime types, max size)
    UploadServlet->>FileStoreService: put(stream, fileName, mimeType)
    FileStoreService->>StorageBackend: Store file + metadata
    StorageBackend-->>FileStoreService: fileId (UUID)
    FileStoreService-->>UploadServlet: fileId
    UploadServlet-->>Client: JSON response with fileId

    Note over Client,TokenIssuer: Download Flow
    Client->>TokenIssuer: Request download token (fileId)
    TokenIssuer-->>Client: JWT (DownloadClaim)
    Client->>DownloadServlet: GET with JWT
    DownloadServlet->>TokenValidator: Validate token
    TokenValidator-->>DownloadServlet: Claims (fileId, disposition)
    DownloadServlet->>FileStoreService: get(fileId)
    FileStoreService->>StorageBackend: Retrieve file
    StorageBackend-->>FileStoreService: InputStream
    FileStoreService-->>DownloadServlet: InputStream
    DownloadServlet-->>Client: File stream + headers
```

## Module Structure

```mermaid
graph LR
    subgraph "API Modules"
        API[filestore-api]
        SAPI[filestore-security-api]
        MAPI[mime-api]
    end

    subgraph "Implementation Modules"
        FSYS[filestore-filesystem]
        RDBMS[filestore-rdbms]
        S3[filestore-s3]
        SEC[filestore-security]
        SERV[filestore-servlet]
        MIME[mime-impl]
        URL[filestore-urlhandler]
    end

    subgraph "Deployment"
        FEAT[features]
        KAR[kar]
    end

    subgraph "Testing"
        ITEST[filestore-itest]
        REPORTS[filestore-reports]
    end

    FSYS --> API
    RDBMS --> API
    S3 --> API
    FSYS --> URL
    RDBMS --> URL
    S3 --> URL
    SEC --> SAPI
    SERV --> API
    SERV --> SAPI
    MIME --> MAPI
    URL --> API
    KAR --> FEAT
    ITEST --> SERV
```

| Module | Packaging | Description |
|--------|-----------|-------------|
| `filestore-api` | bundle | Core `FileStoreService` interface and `FilenameUtils` |
| `filestore-filesystem` | bundle | Filesystem storage backend with Guava metadata cache |
| `filestore-rdbms` | bundle | RDBMS backend via Spring JDBC + LiquiBase migrations |
| `filestore-s3` | bundle | S3-compatible backend (AWS, MinIO) via lightweight client |
| `filestore-urlhandler` | bundle | Custom OSGi URL protocol handler for file access |
| `filestore-security-api` | bundle | JWT security API — `TokenIssuer`, `TokenValidator`, typed claims |
| `filestore-security` | bundle | JWT implementation using JOSE4J |
| `filestore-servlet` | bundle | Upload (multipart) and Download servlets with CORS |
| `mime-api` | bundle | `MimeTypeResolver` interface |
| `mime-impl` | bundle | Default MIME resolver wrapping Apache Sling |
| `features` | feature | Karaf feature descriptors |
| `kar` | kar | Karaf Archive for deployment |
| `filestore-itest` | jar | PAX Exam integration tests on Karaf 4.4.7 |
| `filestore-reports` | pom | JaCoCo coverage aggregation |

## Core API

The `FileStoreService` interface defines the contract all storage backends implement:

```java
public interface FileStoreService {
    String put(InputStream data, String fileName, String mimeType) throws IOException;
    boolean exists(String fileId);
    InputStream get(String fileId) throws IOException;
    String getMimeType(String fileId) throws IOException;
    String getFileName(String fileId) throws IOException;
    long getSize(String fileId) throws IOException;
    Date getCreateTime(String fileId) throws IOException;
    URL getAccessUrl(String fileId) throws IOException;
    String getProtocol();
}
```

**File ID format**: UUID v4 with dashes removed (32 hex characters), e.g. `a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6`.

**Filename sanitization** (`FilenameUtils`):
- Windows reserved names (`CON`, `PRN`, `AUX`, etc.) replaced with `"reserved"`
- Special characters stripped: `$ ( ) + = [ ] # @ ~ , & '`
- Multiple spaces/dots collapsed
- Maximum length: 128 characters

## Storage Backends

All backends are OSGi Declarative Services components with `configurationPolicy = REQUIRE` — they will not activate without OSGi configuration.

### Filesystem Backend

Stores files on the local filesystem with metadata in `.properties` sidecar files. Uses Guava `LoadingCache` for metadata caching (10,000 items, 10-minute TTL).

```mermaid
graph LR
    subgraph "Filesystem Storage"
        DIR["fileSystemStoreDirectory<br/>(default: ~/file-store)"]
        DATA["&lt;fileId&gt;<br/>binary data"]
        META["&lt;fileId&gt;.properties<br/>filename, mimeType, size, createTime"]
    end

    DIR --> DATA
    DIR --> META
```

**Configuration** (`FileSystemFileStoreService.Config`):

| Property | Default | Description |
|----------|---------|-------------|
| `protocol` | *(required)* | URL protocol name (e.g. `judostore`) |
| `fileSystemStoreDirectory` | `~/file-store` | Storage directory path |

### RDBMS Backend

Stores files as BLOBs in a relational database via Spring JDBC. Schema is managed by LiquiBase migrations. Uses Guava `LoadingCache` for metadata caching.

```mermaid
erDiagram
    FILESTORE {
        varchar FILE_ID PK "UUID (32 hex chars)"
        varchar FILE_NAME "Original filename"
        varchar MIME_TYPE "MIME type"
        bigint FILE_SIZE "File size in bytes"
        timestamp CREATE_TIME "Upload timestamp"
        blob FILE_DATA "Binary content"
    }
```

**Configuration** (`RdbmsFileStoreService.Config`):

| Property | Default | Description |
|----------|---------|-------------|
| `protocol` | *(required)* | URL protocol name |
| `table` | `FILESTORE` | Database table name |

Requires a `javax.sql.DataSource` OSGi service to be available.

### S3 Backend

Stores files in any S3-compatible object storage (AWS S3, MinIO, etc.) using the [aws-lightweight-client-java](https://github.com/davidmoten/aws-lightweight-client-java) library (~80KB JAR, no connection pool). Supports hybrid upload: single-part PUT for files under 5MB, multipart streaming for larger files.

```mermaid
graph TB
    subgraph "S3 Upload Strategy"
        PUT["put(InputStream, fileName, mimeType)"]
        BUF["Buffer up to 5MB"]
        SMALL{"totalRead < 5MB?"}
        PROBE{"EOF on probe read?"}
        SP["Single-Part PUT<br/>requestBody(byte[])"]
        MP["Multipart Upload<br/>Streams in 5MB chunks"]
    end

    PUT --> BUF
    BUF --> SMALL
    SMALL -->|Yes| SP
    SMALL -->|No, buffer full| PROBE
    PROBE -->|Yes, exactly 5MB| SP
    PROBE -->|No, more data| MP

    subgraph "S3 Object Metadata"
        M1["x-amz-meta-filename"]
        M2["x-amz-meta-mimetype"]
        M3["x-amz-meta-createtime"]
        M4["x-amz-meta-size<br/>(small files only)"]
    end
```

**Configuration** (`S3FileStoreService.Config`):

| Property | Default | Description |
|----------|---------|-------------|
| `protocol` | *(required)* | URL protocol name (e.g. `s3store`) |
| `bucketName` | *(required)* | S3 bucket name |
| `accessKey` | *(required)* | S3 access key |
| `secretKey` | *(required)* | S3 secret key |
| `endpoint` | *(empty)* | Custom S3 endpoint (for MinIO, LocalStack, etc.) |
| `region` | `us-east-1` | AWS region |

**Size retrieval**: For small files, `getSize()` reads from `META_SIZE` user metadata. For large files uploaded via multipart (where `META_SIZE` is not stored), it falls back to the S3 object's native `Content-Length` header.

## Security Layer

JWT-based token security using [JOSE4J](https://bitbucket.org/b_c/jose4j). Tokens carry typed claims for upload and download operations.

```mermaid
graph LR
    subgraph "Token Flow"
        TI["TokenIssuer"]
        TV["TokenValidator"]
    end

    subgraph "Upload Claims"
        UC1["FILE_MIME_TYPE_LIST"]
        UC2["MAX_FILE_SIZE"]
        UC3["CONTEXT"]
    end

    subgraph "Download Claims"
        DC1["FILE_ID"]
        DC2["FILE_NAME"]
        DC3["FILE_SIZE"]
        DC4["FILE_MIME_TYPE"]
        DC5["DISPOSITION"]
        DC6["CONTEXT"]
    end

    TI -->|createUploadToken| UC1
    TI -->|createUploadToken| UC2
    TI -->|createUploadToken| UC3
    TI -->|createDownloadToken| DC1
    TI -->|createDownloadToken| DC2
    TI -->|createDownloadToken| DC3
    TI -->|createDownloadToken| DC4
    TI -->|createDownloadToken| DC5
    TI -->|createDownloadToken| DC6
    TV -->|parseUploadToken| UC1
    TV -->|parseDownloadToken| DC1
```

**Token configuration** (`TokenServiceConfig`):

| Property | Description |
|----------|-------------|
| `algorithm` | JWT signing algorithm |
| `issuer` | Token issuer identifier |
| `audiencePrefix` | Audience prefix (appended with "Upload" or "Download") |
| `expirationTime` | Token validity in minutes |

## HTTP Servlets

### Upload Servlet

Handles multipart file uploads with configurable size limits, token validation, and CORS support. Returns JSON responses with file metadata.

**Configuration** (`UploadServlet.Config`):

| Property | Default | Description |
|----------|---------|-------------|
| `servletPath` | *(required)* | URL path to register servlet |
| `maxSize` | default limit | Maximum request size (KB) |
| `maxFileSize` | default limit | Maximum individual file size (KB) |
| `slowUploads` | default delay | Slow upload throttle (ms) |
| `noDataTimeout` | `20000` | No-data timeout (ms) |
| `tokenRequired` | `false` | Require JWT token for uploads |
| `allowOrigin` | | CORS allowed origins |
| `allowCredentials` | | CORS allow credentials |
| `allowHeaders` | | CORS allowed headers |
| `exposeHeaders` | | CORS exposed headers |
| `maxAge` | | CORS preflight max age |

**Internationalized error messages**: English, Spanish, Italian, Danish, Russian.

### Download Servlet

Streams files to clients with proper Content-Type and Content-Disposition headers.

**Configuration** (`DownloadServlet.Config`):

| Property | Default | Description |
|----------|---------|-------------|
| `servletPath` | *(required)* | URL path to register servlet |
| `tokenRequired` | `false` | Require JWT token for downloads |
| CORS settings | | Same CORS options as upload servlet |

## URL Protocol Handlers

Each storage backend registers a custom URL protocol via OSGi's `URLStreamHandlerService`. This enables file access through standard `java.net.URL` objects.

```mermaid
graph LR
    URL["new URL('s3store:abc123-report.pdf')"] --> USH[FileStoreUrlStreamHandler]
    USH --> FSUC[FileStoreUrlConnection]
    FSUC --> FSS[FileStoreService.get]
    FSS --> STREAM[InputStream]
```

Example URLs:
- `judostore:<fileId>-<fileName>` (filesystem)
- `s3store:<fileId>-<fileName>` (S3)
- `rdbmsstore:<fileId>-<fileName>` (RDBMS)

The protocol name is configurable per backend instance.

## MIME Type Resolution

Wraps Apache Sling's `MimeTypeService` with a project-local `MimeTypeResolver` interface.

- Auto-detects MIME type from filename extension when not explicitly provided
- Falls back to `application/octet-stream` for unknown types
- Used by all storage backends during `put()` to ensure consistent MIME metadata

## Karaf Deployment

Features are defined for granular deployment into Apache Karaf 4.4.7.

```mermaid
graph TB
    FULL["filestore-full"]
    FSYS["filestore-filesystem"]
    S3["filestore-s3"]
    RDBMS["filestore-rdbms"]
    SERV["filestore-servlet"]
    SEC["filestore-security"]
    BASE["filestore-base"]
    DEPS["filestore-dependencies"]

    FULL --> FSYS
    FULL --> S3
    FULL --> RDBMS
    FULL --> SERV
    FULL --> SEC

    FSYS --> BASE
    S3 --> BASE
    RDBMS --> BASE
    SERV --> SEC

    BASE --> DEPS
```

Install individual features:
```
feature:install filestore-filesystem
feature:install filestore-s3
feature:install filestore-rdbms
feature:install filestore-servlet
```

Or install everything:
```
feature:install filestore-full
```

The `kar` module packages all features as a Karaf Archive (`.kar`) for drop-in deployment.

## Building

### Prerequisites

- Java 21
- Maven 3.8+ (or use the included wrapper)
- Docker (for S3 and RDBMS tests — testcontainers)

### Build Commands

```bash
# Full build
./mvnw clean install

# Build skipping tests
./mvnw clean install -DskipTests

# Build a single module (parent POM must be installed first)
./mvnw test -pl filestore-s3

# Run a single test class
./mvnw test -pl filestore-s3 -Dtest=S3FileStoreServiceTest

# Run a single test method
./mvnw test -pl filestore-s3 -Dtest=S3FileStoreServiceTest#testPutAndGetWithNullMimeType

# Install parent POM only (fixes dependency resolution issues)
./mvnw install -N -DskipTests
```

All modules use `<packaging>bundle</packaging>` via `maven-bundle-plugin`. The `flatten-maven-plugin` generates `.flattened-pom.xml` files for CI-friendly `${revision}` versioning — do not delete these.

If you encounter "Unknown packaging: bundle" errors, run a full build from the project root first.

## Testing

```mermaid
graph TB
    subgraph "Unit Tests (JUnit 5 + Mockito)"
        FS_TEST["FileSystemFileStoreServiceTest<br/>Mocked MimeTypeService"]
        S3_TEST["S3FileStoreServiceTest<br/>MinIO testcontainer"]
        RDBMS_TEST["RdbmsFilestoreTest<br/>PostgreSQL testcontainer"]
    end

    subgraph "Integration Tests (PAX Exam)"
        ITEST["FilestoreTest<br/>Karaf 4.4.7 container"]
    end

    subgraph "Coverage"
        JACOCO["filestore-reports<br/>JaCoCo aggregation"]
    end

    FS_TEST -.-> JACOCO
    S3_TEST -.-> JACOCO
    RDBMS_TEST -.-> JACOCO
    ITEST -.-> JACOCO
```

| Module | Test Type | Container | What's Tested |
|--------|-----------|-----------|---------------|
| `filestore-filesystem` | Unit | None (Mockito) | Put/get, metadata, filename resolution |
| `filestore-s3` | Unit | MinIO (testcontainers) | Small/large/empty file upload, multipart, null ID, size fallback |
| `filestore-rdbms` | Unit | PostgreSQL (testcontainers) | Database-backed storage |
| `filestore-itest` | Integration | Karaf 4.4.7 (PAX Exam) | Upload/download servlets, JWT tokens, CORS |

Docker must be running for S3 and RDBMS tests.

## Configuration Reference

All components use `configurationPolicy = REQUIRE` and will not activate without configuration. In Karaf, create `.cfg` files in `etc/`:

### Filesystem Backend

```properties
# etc/hu.blackbelt.osgi.filestore.filesystem.FileSystemFileStoreService.cfg
protocol=judostore
fileSystemStoreDirectory=/var/data/filestore
```

### RDBMS Backend

```properties
# etc/hu.blackbelt.osgi.filestore.rdbms.RdbmsFileStoreService.cfg
protocol=rdbmsstore
table=FILESTORE
```

### S3 Backend

```properties
# etc/hu.blackbelt.osgi.filestore.s3.S3FileStoreService.cfg
protocol=s3store
bucketName=my-filestore-bucket
accessKey=AKIAIOSFODNN7EXAMPLE
secretKey=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
region=eu-west-1
# For MinIO or custom S3-compatible storage:
# endpoint=http://minio.local:9000
```

### Upload Servlet

```properties
# etc/hu.blackbelt.osgi.filestore.servlet.UploadServlet.cfg
servletPath=/filestore/upload
tokenRequired=true
maxSize=102400
maxFileSize=51200
allowOrigin=*
```

### Download Servlet

```properties
# etc/hu.blackbelt.osgi.filestore.servlet.DownloadServlet.cfg
servletPath=/filestore/download
tokenRequired=true
allowOrigin=*
```

### Token Service

```properties
# etc/hu.blackbelt.osgi.filestore.security.DefaultTokenIssuer.cfg
issuer=my-app
audiencePrefix=MyApp
expirationTime=30
```

## Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Java | 21 | Runtime |
| Apache Karaf | 4.4.7 | OSGi container |
| Guava | 30.1-jre | Metadata caching (`LoadingCache`) |
| Spring JDBC | 4.1.2.RELEASE | RDBMS backend |
| aws-lightweight-client-java | 0.1.24 | S3 backend (~80KB, no connection pool) |
| JOSE4J | 0.7.2 | JWT token signing/validation |
| Apache Sling MIME | 2.1.8 | MIME type resolution |
| Apache Commons FileUpload | 1.3.3 | Multipart upload parsing |
| Lombok | 1.18.34 | Boilerplate reduction |
| TestContainers | 1.21.4 | MinIO and PostgreSQL test containers |
| PAX Exam | 4.13.5 | Karaf integration testing |

## License

Licensed under the [Apache License, Version 2.0](LICENSE.txt).

Copyright (C) 2018 - 2023 [BlackBelt Technology](https://github.com/BlackBeltTechnology).
