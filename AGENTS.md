# OSGi FileStore - Project Documentation

## Project Overview


**Repository:** BlackBeltTechnology/osgi-filestore
**License:** Apache License 2.0
**Java Version:** 21
**Build System:** Maven 3.8.x with Felix Bundle Plugin (OSGi) and Karaf Maven Plugin

1. Provides a unified `FileStoreService` API for blob-based file storage with pluggable backends (RDBMS, filesystem, S3)
2. Runs as OSGi bundles in Apache Karaf, using Declarative Services for dependency injection and configuration
3. Includes HTTP servlets for file upload/download with multipart support, CORS, and configurable size limits
4. Offers JWT-based security for token-validated uploads and downloads using jose4j
5. Provides a custom OSGi URL stream handler so files can be accessed via `protocol:fileId` URLs

## Code Instructions

1. First think through the problem, read the codebase for relevant files.
2. Before you make any major changes, check in with me and I will verify the plan.
3. Please every step of the way just give me a high level explanation of what changes you made.
4. Make every task and code change you do as simple as possible. We want to avoid making any massive or complex changes. Every change should impact as little code as possible. Everything is about simplicity.
5. Maintain a documentation file that describes how the architecture of the app works inside and out.
6. Never speculate about code you have not opened. If the user references a specific file, you MUST read the file before answering. Make sure to investigate and read relevant files BEFORE answering questions about the codebase. Never make any claims about code before investigating unless you are certain of the correct answer - give grounded and hallucination-free answers.
7. For implementation use TDD (Test-Driven Development): write or update tests first to define the expected behaviour, verify they fail, then write the minimal implementation to make them pass.
8. Use DRY (Don't Repeat Yourself): extract reusable logic into separate classes, utilities, or components. If the same pattern appears in multiple places, refactor it into a shared helper.

## Directory Structure

```
osgi-filestore/
├── filestore-api/           # Core FileStoreService interface
├── filestore-filesystem/    # Filesystem storage backend
├── filestore-rdbms/         # RDBMS storage backend (Spring JDBC + Liquibase)
├── filestore-s3/            # S3 cloud storage backend
├── filestore-servlet/       # Upload & Download HTTP servlets
├── filestore-security-api/  # Token/JWT interfaces (TokenValidator, TokenIssuer)
├── filestore-security/      # JWT implementation using jose4j
├── filestore-urlhandler/    # OSGi URL stream handler
├── mime-api/                # MimeTypeResolver interface
├── mime-impl/               # MIME implementation (Apache Sling)
├── features/                # Karaf feature definitions (feature.xml)
├── kar/                     # Karaf archive packaging
├── filestore-itest/         # Integration tests (Pax Exam + Karaf)
├── filestore-reports/       # JaCoCo coverage aggregation
└── .github/workflows/       # CI/CD pipelines
```

## Core Modules

### API Layer

| Module | Type | Purpose |
|--------|------|---------|
| `filestore-api/` | Interface | Defines `FileStoreService` — the central contract for put/get/exists/metadata operations on files identified by UUID strings |
| `mime-api/` | Interface | Defines `MimeTypeResolver` for extension↔MIME type mapping |
| `filestore-security-api/` | Interface | Defines `TokenValidator`, `TokenIssuer`, `Token<Claim>`, `UploadClaim`, `DownloadClaim` for JWT-based access control |

### Implementation Layer

| Module | Type | Purpose |
|--------|------|---------|
| `filestore-filesystem/` | OSGi Bundle | Stores files on disk in nested 2-char directories with `.properties` sidecar metadata files. Configurable root dir (default: `~/file-store`) |
| `filestore-rdbms/` | OSGi Bundle | Stores files in a `FILESTORE` database table (LONGBLOB). Uses Spring JDBC, Liquibase for schema, Guava cache for metadata (10k entries, 10-min TTL) |
| `filestore-s3/` | OSGi Bundle | S3 cloud storage backend |
| `mime-impl/` | OSGi Bundle | MIME type resolution using Apache Sling Commons MIME |
| `filestore-security/` | OSGi Bundle | JWT token creation and validation using jose4j. `DefaultTokenValidator` and `DefaultTokenIssuer` |

### Integration Layer

| Module | Type | Purpose |
|--------|------|---------|
| `filestore-servlet/` | OSGi Bundle | `UploadServlet` (multipart POST with progress tracking) and `DownloadServlet` (GET with Content-Disposition). CORS support, configurable size limits, optional token enforcement |
| `filestore-urlhandler/` | OSGi Bundle | Registers a custom URL protocol handler so files can be accessed as `protocol:uuid-filename` URLs within OSGi |

### Packaging & Testing

| Module | Type | Purpose |
|--------|------|---------|
| `features/` | Karaf Feature | Defines modular Karaf features: `filestore-base`, `filestore-filesystem`, `filestore-rdbms`, `filestore-s3`, `filestore-servlet`, `filestore-security`, `filestore-full` |
| `kar/` | Karaf Archive | Packages features into a deployable `.kar` file |
| `filestore-itest/` | Integration Test | Boots a Karaf container via Pax Exam and tests OSGi service wiring end-to-end |
| `filestore-reports/` | Reports | Aggregates JaCoCo code coverage across modules |

## Technology Stack

### Core Technologies
- **OSGi** Core 6.0.0 + Declarative Services 1.3.0 — service registration, DI, configuration
- **Apache Karaf** 4.4.7 — OSGi container and feature deployment
- **Spring JDBC** — database access in RDBMS backend (JdbcTemplate, DefaultLobHandler)
- **Liquibase** — database schema management (via osgi-liquibase)
- **Google Guava** 30.1-jre — LoadingCache for metadata caching
- **jose4j** 0.7.2 — JWT token creation and validation
- **Apache Commons FileUpload** 1.3.3 — multipart HTTP upload parsing
- **Apache Sling Commons MIME** 2.1.8 — MIME type resolution
- **Lombok** 1.18.34 — boilerplate reduction (@Getter, @Setter, @Builder, @Slf4j)

### Build & Quality
- **Maven** 3.8.x (wrapper: `./mvnw`)
- **Felix Maven Bundle Plugin** — generates OSGi bundle manifests
- **JUnit Jupiter** 5.9.1 — unit testing
- **Mockito** 4.8.0 — mocking
- **Hamcrest** 2.2 — assertions
- **Pax Exam** 4.13.5 — OSGi integration testing in Karaf
- **TestContainers** 1.21.1 — PostgreSQL containers for RDBMS tests
- **JaCoCo** 0.8.12 — code coverage
- **SonarQube** — static analysis (runs on develop)

## Build Commands

```sh
# Run all unit tests
mvn clean test

# Full build (compile + test + package + install)
mvn clean install

# Build a single module
mvn clean test -pl filestore-rdbms

# Run a single test class
mvn clean test -pl filestore-rdbms -Dtest=RdbmsFilestoreTest

# Skip integration tests (they require Karaf + Pax Exam)
mvn clean install -pl '!filestore-itest'

# Use Maven wrapper (cross-platform)
./mvnw clean install
```

### Maven Profiles

| Profile | Purpose |
|---------|---------|
| `modules` | Module selection (active by default) |
| `sign-artifacts` | GPG-sign artifacts for release |
| `release-dummy` | Deploy to `/tmp` for local testing |
| `release-judong` | Deploy to Judo Nexus repository |
| `release-central` | Deploy to Maven Central (OSS Sonatype) |
| `generate-github-asciidoc-diagrams` | Generate PlantUML/Ditaa diagrams |
| `update-source-code-license` | Update Apache 2.0 license headers |

## Key Configuration Files

| File | Purpose |
|------|---------|
| `pom.xml` | Root POM — defines all modules, dependency versions, plugin management |
| `features/src/main/feature/feature.xml` | Karaf feature definitions — declares bundles, dependencies, and feature composition |
| `filestore-rdbms/src/main/resources/liquibase/changelog.xml` | Liquibase changelog for RDBMS schema (FILESTORE table) |
| `filestore-rdbms/src/main/resources/config-templates/` | OSGi configuration templates for RDBMS backend |
| `filestore-filesystem/src/main/resources/config-templates/` | OSGi configuration templates for filesystem backend |
| `logback-test.xml` | Shared test logging configuration |
| `.github/workflows/build.yml` | Main CI/CD pipeline |
| `.github/workflows/release.yml` | Manual release trigger |

## Development Environment

**Required:**
- Java 21 JDK (Azul Zulu recommended)
- Maven 3.8.x+
- Git

**For RDBMS tests:**
- Docker (TestContainers starts PostgreSQL automatically)

**For integration tests:**
- Full project must be installed first (`mvn clean install`) before running `filestore-itest`

## Git Workflow

- **Main Branch:** `develop`
- **Versioning:** Semantic versioning — `1.3.1-SNAPSHOT` (CI appends `date_commitId_branch` for develop builds)
- **Branching model:** GitFlow — `develop`, `feature/JNG-xxx`, `release/x.y.z`, `bugfix/JNG-xxx`, `hotfix/JNG-xxx`, `master`
- **Commit policy:** Every commit must reference a JIRA ticket (`JNG-xxx`)

## Important Notes

1. All modules produce OSGi bundles (`<packaging>bundle</packaging>`) — the Felix Bundle Plugin generates `MANIFEST.MF` with proper Import-Package/Export-Package headers
2. OSGi services use `@Component(immediate=true, configurationPolicy=ConfigurationPolicy.REQUIRE)` — they will not activate without OSGi Config Admin configuration
3. The RDBMS backend requires a `DataSource` OSGi service and Liquibase for initial schema setup
4. Metadata caching (Guava LoadingCache) is used by both RDBMS and filesystem backends — cache entries expire after 10 minutes
5. The servlet layer is optional — `FileStoreService` can be used directly as an OSGi service without HTTP
6. Token enforcement (`tokenRequired`) is configurable per servlet — when disabled, files are accessible without JWT
7. The URL handler module allows other OSGi bundles to access files via standard `java.net.URL` using the configured protocol scheme
8. Integration tests boot a full Karaf container — they are slow and should be run after a full `mvn install`

## Related Documentation

- [README.md](README.md) — Project overview, architecture diagrams, and API summary
- [CONTRIBUTING.md](CONTRIBUTING.md) — Development setup, build commands, branching, and submission guidelines
- [.github/CIFLOW.md](.github/CIFLOW.md) — CI/CD pipeline workflow diagrams
