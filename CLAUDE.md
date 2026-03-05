# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OSGi-based blob file store service with pluggable storage backends (filesystem, RDBMS, S3). All implementations are OSGi Declarative Services components, independently deployable as Karaf features. JWT-based security for upload/download via servlets.

## Build Commands

Most modules use `<packaging>bundle</packaging>` (from `maven-bundle-plugin`). The `flatten-maven-plugin` generates `.flattened-pom.xml` files — **do not delete these** as they are needed for CI-friendly `${revision}` versioning. If the build breaks with "Unknown packaging: bundle", run the full build from the project root first to restore proper state.

```bash
# Full build (from project root)
./mvnw clean install

# Build skipping tests
./mvnw clean install -DskipTests

# Run all tests for a single module (must run from project root)
./mvnw test -pl filestore-s3

# Run a single test class
./mvnw test -pl filestore-s3 -Dtest=S3FileStoreServiceTest

# Run a single test method
./mvnw test -pl filestore-s3 -Dtest=S3FileStoreServiceTest#testPutAndGetWithNullMimeType

# Install parent POM only (needed if dependency resolution breaks)
./mvnw install -N -DskipTests
```

**Important**: The `-pl` flag requires the parent POM to be installed in the local Maven repo. If you get "Unknown packaging: bundle" errors, run the full `./mvnw clean install -DskipTests` first.

## Architecture

**Core interface**: `FileStoreService` in `filestore-api` — defines `put()`, `get()`, `exists()`, `getFileName()`, `getMimeType()`, `getSize()`, `getCreateTime()`, `getAccessUrl()`.

**Three storage implementations**, each an OSGi component with `configurationPolicy = REQUIRE`:
- `filestore-filesystem` — stores files on disk, Guava `LoadingCache` for metadata
- `filestore-rdbms` — Spring JDBC + LiquiBase migrations, configurable table name
- `filestore-s3` — AWS SDK v2, supports MinIO/custom endpoints

**URL protocol handler**: `filestore-urlhandler` — each implementation registers a custom URL protocol (e.g., `s3store:`, `judostore:`) for file access via OSGi URL stream handler service.

**Security layer**: `filestore-security-api` + `filestore-security` — JWT tokens (JOSE4J) with `TokenIssuer`/`TokenValidator` and typed claims (`UploadClaim`, `DownloadClaim`).

**HTTP layer**: `filestore-servlet` — `UploadServlet` (multipart) and `DownloadServlet` with CORS support.

**Deployment**: `features` module defines Karaf features; `kar` packages them as Karaf Archives.

## Key Patterns

- File IDs are UUID v4 with dashes removed
- All OSGi components use `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)` — they won't activate without configuration
- Metadata (filename, MIME type, create time) is stored alongside file data in each backend
- `MimeTypeService` (Apache Sling) is injected via `@Reference` for MIME type resolution

## Testing

- **Unit tests**: JUnit 5 + Mockito. S3 tests use testcontainers with MinIO (`MinioSingletonExtension`/`MinioFixture`). RDBMS tests use YugabyteDB container.
- **Integration tests**: `filestore-itest` uses PAX Exam with a real Karaf 4.4.7 container.
- Docker must be running for S3 and RDBMS tests (testcontainers).

## Key Dependencies

- Java 21, Maven 3.8.x
- AWS SDK v2 (2.31.1) for S3
- Spring JDBC (4.1.2.RELEASE) for RDBMS
- Guava (30.1-jre) for caching
- Lombok (1.18.34) for boilerplate
- JOSE4J (0.7.2) for JWT
