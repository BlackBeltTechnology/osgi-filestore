# OSGi FileStore

[![Build](https://github.com/BlackBeltTechnology/osgi-filestore/actions/workflows/build.yml/badge.svg?branch=develop)](https://github.com/BlackBeltTechnology/osgi-filestore/actions/workflows/build.yml)

## Introduction

OSGi FileStore is a blob-based file storage service that provides a unified API for storing and retrieving files across multiple backends — RDBMS, filesystem, and S3. It runs inside an Apache Karaf OSGi container and includes HTTP servlets for upload/download, JWT-based security, and custom URL protocol handling.

The project is designed as a set of loosely coupled OSGi bundles, so you can deploy only the storage backend and features you need.

## Module Overview

```mermaid
graph TD
    API[filestore-api<br/>Core Interface] --> FS[filestore-filesystem<br/>File System Backend]
    API --> RDBMS[filestore-rdbms<br/>RDBMS Backend]
    API --> S3[filestore-s3<br/>S3 Backend]
    API --> SERVLET[filestore-servlet<br/>Upload/Download Servlets]
    API --> URL[filestore-urlhandler<br/>URL Protocol Handler]

    MIME_API[mime-api<br/>MIME Resolver Interface] --> MIME_IMPL[mime-impl<br/>MIME Implementation]
    MIME_API --> FS
    MIME_API --> RDBMS

    SEC_API[filestore-security-api<br/>Token Interfaces] --> SEC[filestore-security<br/>JWT Implementation]
    SEC_API --> SERVLET

    FEATURES[features<br/>Karaf Feature Definitions] --> KAR[kar<br/>Karaf Archive]

    ITEST[filestore-itest<br/>Integration Tests] -.->|tests| API
    ITEST -.->|tests| FS
    ITEST -.->|tests| RDBMS
```

## Core API

The central interface is `FileStoreService`, which all storage backends implement:

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

Files are identified by UUID-based string IDs returned from `put()`.

## Storage Backends

### RDBMS (`filestore-rdbms`)

Stores files in a database table (default: `FILESTORE`) using Spring JDBC. Schema is managed by Liquibase. Metadata is cached with Guava LoadingCache (10k entries, 10-minute TTL).

| Column | Type | Description |
|--------|------|-------------|
| `FILE_ID` | VARCHAR(255), PK | UUID identifier |
| `FILENAME` | VARCHAR(255) | Original filename |
| `DATA` | LONGBLOB | File content |
| `SIZE` | BIGINT | File size in bytes |
| `MIME_TYPE` | VARCHAR(255) | Content type |
| `CREATE_TIME` | TIMESTAMP | Creation time |

### Filesystem (`filestore-filesystem`)

Stores files on disk in a nested 2-character directory structure derived from the UUID. Each file has a companion `file.properties` sidecar for metadata. Default root: `~/file-store`.

### S3 (`filestore-s3`)

Cloud storage backend using Amazon S3.

## Servlet Layer

Two HTTP servlets handle file transfer over HTTP:

- **UploadServlet** — receives multipart uploads, validates MIME types against upload tokens, returns JSON with file metadata and download tokens
- **DownloadServlet** — serves files with proper Content-Type and Content-Disposition headers, supports token-based access control

Both servlets include CORS support and configurable size limits.

## Security

JWT-based security using jose4j. The `TokenIssuer` creates upload/download tokens with claims (allowed MIME types, max file size, file ID, etc.), and `TokenValidator` verifies them. Token enforcement is optional per-servlet.

## Runtime Flow

```mermaid
sequenceDiagram
    participant Client
    participant UploadServlet
    participant TokenValidator
    participant FileStoreService
    participant TokenIssuer

    Client->>UploadServlet: POST multipart (+ X-Token header)
    UploadServlet->>TokenValidator: parseUploadToken(token)
    TokenValidator-->>UploadServlet: Token<UploadClaim>
    UploadServlet->>FileStoreService: put(inputStream, fileName, mimeType)
    FileStoreService-->>UploadServlet: fileId (UUID)
    UploadServlet->>TokenIssuer: createDownloadToken(claims)
    TokenIssuer-->>UploadServlet: downloadToken
    UploadServlet-->>Client: JSON {fileId, downloadToken, ...}
```

```mermaid
sequenceDiagram
    participant Client
    participant DownloadServlet
    participant TokenValidator
    participant FileStoreService

    Client->>DownloadServlet: GET /download?fileId=xxx (+ X-Token)
    DownloadServlet->>TokenValidator: parseDownloadToken(token)
    TokenValidator-->>DownloadServlet: Token<DownloadClaim>
    DownloadServlet->>FileStoreService: get(fileId)
    FileStoreService-->>DownloadServlet: InputStream
    DownloadServlet-->>Client: file content (with Content-Type, Content-Disposition)
```

## Dependency Graph

```mermaid
graph LR
    subgraph External
        OSGi[OSGi DS 1.3.0]
        Spring[Spring JDBC]
        Liquibase[Liquibase]
        Guava[Guava 30.1]
        Sling[Apache Sling MIME]
        Jose4j[jose4j 0.7.2]
        CommonsUpload[Commons FileUpload]
    end
    subgraph Project
        API[filestore-api]
        RDBMS[filestore-rdbms] --> Spring
        RDBMS --> Liquibase
        RDBMS --> Guava
        FS[filestore-filesystem] --> Guava
        Servlet[filestore-servlet] --> CommonsUpload
        Security[filestore-security] --> Jose4j
        MIME[mime-impl] --> Sling
    end
    API --> OSGi
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, submission guidelines, and CI/CD workflow details.

## License

This project is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
