# RDBMS FileStore Specification

## Purpose

Provides a relational database-backed implementation of the `FileStoreService` interface, storing file content as BLOBs and metadata in a configurable database table managed by Liquibase, with Guava-based caching for metadata lookups and Spring JDBC for data access.

## Architecture

### Key Classes and Relationships

- **`RdbmsFileStoreService`** (`hu.blackbelt.osgi.filestore.rdbms`) -- Implements `FileStoreService`. Declared as `@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)` with `@Designate(ocd = RdbmsFileStoreService.Config.class)`. Uses Spring `JdbcTemplate` for all database operations.
- **`RdbmsFileStoreLiquibaseExecutor`** (`hu.blackbelt.osgi.filestore.rdbms`) -- Separate `@Component` that runs the Liquibase changelog (`liquibase/changelog.xml`) on activation to create the storage table. Takes a configurable table name parameter (`table-name`) passed to the changelog.
- **`FileEntity`** (`hu.blackbelt.osgi.filestore.rdbms.helper`) -- Immutable-style DTO created via `FileEntity.createEntity(String filename, String mimeType, InputStream data)`. Generates a `UUID.randomUUID().toString()` as `fileId`, captures `Timestamp createTime`, computes `size` from `InputStream.available()`, and exposes `getCallback(LobHandler)` to produce a `FilestorePreparedStatementCallback`.
- **`FilestoreHelper`** (`hu.blackbelt.osgi.filestore.rdbms.helper`) -- Static utility providing SQL-generation methods: `insert(String tableName)`, `count(String tableName, String fileId)`, `read(String tableName, String fileId, String colName)`, `meta(String tableName, String fileId)`. Defines column name constants: `FILENAME_FIELD`, `MIME_TYPE_FIELD`, `DATA_FIELD`, `SIZE_FIELD`, `CREATE_TIME_FIELD`.
- **`FilestorePreparedStatementCallback`** (`hu.blackbelt.osgi.filestore.rdbms.helper`) -- Extends `AbstractLobCreatingPreparedStatementCallback`. Binds `FileEntity` fields to the INSERT prepared statement, using `LobCreator.setBlobAsBinaryStream` for the DATA column.
- **`FileStoreService`** (`hu.blackbelt.osgi.filestore.api`) -- The shared API interface.
- **`FilenameUtils`** (`hu.blackbelt.osgi.filestore.api`) -- Sanitises file names via `makeValidFilename(String)`.
- **`MimeTypeService`** (Apache Sling) -- `@Reference`-injected for MIME type detection.
- **`DataSource`** (`javax.sql`) -- `@Reference`-injected; used by both `RdbmsFileStoreService` (to create `JdbcTemplate`) and `RdbmsFileStoreLiquibaseExecutor` (to obtain a `Connection` for Liquibase).

### Configuration

**`RdbmsFileStoreService.Config`**:

| Attribute    | Description                     | Default       |
|--------------|---------------------------------|---------------|
| `protocol()` | Protocol for URL stream handler | (required)    |
| `table()`    | Table name for file storage     | `"FILESTORE"` |

**`RdbmsFileStoreLiquibaseExecutor.Config`**:

| Attribute    | Description                     | Default       |
|--------------|---------------------------------|---------------|
| `table()`    | Table name for file storage     | `"FILESTORE"` |

### Database Schema (Liquibase `changelog.xml`)

Table name is parameterised as `${table-name}`:

| Column        | Type           | Constraints              |
|---------------|----------------|--------------------------|
| `FILE_ID`     | `VARCHAR(255)` | NOT NULL, PRIMARY KEY    |
| `FILENAME`    | `VARCHAR(255)` | NOT NULL                 |
| `DATA`        | `LONGBLOB`     | NOT NULL                 |
| `SIZE`        | `BIGINT`       | NOT NULL                 |
| `MIME_TYPE`   | `VARCHAR(255)` | NOT NULL                 |
| `CREATE_TIME` | `TIMESTAMP`    | NOT NULL                 |

### Caching

A `LoadingCache<String, Map<String, ?>>` (`metaCache`) with `maximumSize = 10000` and `expireAfterWrite = 10 minutes` caches metadata maps (keys: `FILENAME`, `MIME_TYPE`, `CREATE_TIME`, `SIZE`) loaded via the private `getMeta(String)` method which executes the `meta()` SQL query.

## Requirements

### Requirement: Liquibase Schema Provisioning

The `RdbmsFileStoreLiquibaseExecutor` SHALL execute `liquibase/changelog.xml` on component activation, passing the configured table name (uppercased) as the `table-name` Liquibase parameter, to create the storage table if it does not exist.

#### Scenario: First activation creates the table
- **GIVEN** a `DataSource` and `LiquibaseExecutor` are available and the database has no filestore table
- **WHEN** `RdbmsFileStoreLiquibaseExecutor.activate(BundleContext, Config)` is called with `config.table() = "FILESTORE"`
- **THEN** `liquibaseExecutor.executeLiquibaseScript(connection, "liquibase/changelog.xml", bundle, {table-name: "FILESTORE"})` is invoked and the `FILESTORE` table is created with columns `FILE_ID`, `FILENAME`, `DATA`, `SIZE`, `MIME_TYPE`, `CREATE_TIME`

#### Scenario: Liquibase error is logged but does not propagate
- **GIVEN** the Liquibase script execution throws a `LiquibaseException`
- **WHEN** activation occurs
- **THEN** the exception is caught and logged via `log.error("Could not execute liquibase script", e)` without propagating

### Requirement: File Storage with UUID and BLOB Insertion

The service SHALL create a `FileEntity` with a UUID-based `fileId`, persist it via `JdbcTemplate.execute` using the `FilestorePreparedStatementCallback`, storing file content as a BLOB in the `DATA` column.

#### Scenario: Store a file with explicit fileName and mimeType
- **GIVEN** `RdbmsFileStoreService` is activated with `table = "FILESTORE"` and a valid `DataSource`
- **WHEN** `put(inputStream, "report.pdf", "application/pdf")` is called
- **THEN** a UUID-format `fileId` is returned, and a row is inserted into `FILESTORE` with `FILE_ID = <uuid>`, `FILENAME = "report.pdf"`, `MIME_TYPE = "application/pdf"`, `SIZE = <stream-available-bytes>`, `CREATE_TIME = <current-timestamp>`, and `DATA = <blob-content>`

#### Scenario: Store a file with null fileName and known mimeType
- **GIVEN** `MimeTypeService.getExtension("image/png")` returns `"png"`
- **WHEN** `put(inputStream, null, "image/png")` is called
- **THEN** the `FILENAME` column is set to `<fileId>.png`

#### Scenario: Store a file with both fileName and mimeType null
- **GIVEN** `MimeTypeService.getMimeType("<fileId>.bin")` returns `null`
- **WHEN** `put(inputStream, null, null)` is called
- **THEN** `FILENAME` is `<fileId>.bin` and `MIME_TYPE` is `"application/octet-stream"`

### Requirement: File Existence Check via COUNT Query

The service SHALL determine file existence by executing `SELECT COUNT(*) FROM <table> WHERE FILE_ID = '<fileId>'` and returning `true` if the count equals 1.

#### Scenario: Existing file returns true
- **GIVEN** a row with `FILE_ID = "abc-123"` exists in the `FILESTORE` table
- **WHEN** `exists("abc-123")` is called
- **THEN** the method returns `true`

#### Scenario: Non-existent file returns false
- **GIVEN** no row with `FILE_ID = "missing"` exists in the table
- **WHEN** `exists("missing")` is called
- **THEN** the method returns `false`

### Requirement: File Content Retrieval as BinaryStream

The service SHALL retrieve file content by executing `SELECT DATA FROM <table> WHERE FILE_ID = '<fileId>'` and returning `ResultSet.getBinaryStream("DATA")`.

#### Scenario: Retrieve stored file content
- **GIVEN** a file with content bytes was stored with `fileId = "abc-123"`
- **WHEN** `get("abc-123")` is called
- **THEN** the returned `InputStream` yields the stored BLOB content

### Requirement: Cached Metadata Access

The service SHALL serve `getMimeType(String)`, `getFileName(String)`, `getSize(String)`, and `getCreateTime(String)` from the Guava `metaCache`. On cache miss, the private `getMeta(String)` method SHALL execute `SELECT FILENAME, MIME_TYPE, SIZE, CREATE_TIME FROM <table> WHERE FILE_ID = '<fileId>'` and cache the result as an `ImmutableMap`.

#### Scenario: Metadata served from cache on second access
- **GIVEN** a file was stored with `MIME_TYPE = "text/csv"` and `getMimeType(fileId)` was called once (populating the cache)
- **WHEN** `getMimeType(fileId)` is called again within 10 minutes
- **THEN** `"text/csv"` is returned without executing a database query

#### Scenario: Non-existent fileId throws IllegalArgumentException
- **GIVEN** no file exists with `fileId = "nonexistent"`
- **WHEN** `getFileName("nonexistent")` is called
- **THEN** an `IllegalArgumentException` is thrown with message `"No file found with the given id."`

### Requirement: Protocol-Prefixed ID Stripping

The service SHALL accept fileIds containing a colon (e.g., `"judostore:abc-123"`) and strip everything before and including the first colon via `getStrippedId(String)`.

#### Scenario: Access file using protocol-prefixed ID
- **GIVEN** a file was stored with `fileId = "abc-123"` and `protocol = "judostore"`
- **WHEN** `get("judostore:abc-123")` is called
- **THEN** the query uses `FILE_ID = 'abc-123'` and file content is returned

### Requirement: Access URL Generation

The service SHALL return a `URL` in the format `<protocol>:<fileId>-<fileName>` from `getAccessUrl(String)`.

#### Scenario: Generate access URL
- **GIVEN** a file with `fileId = "abc-123"`, `FILENAME = "report.pdf"`, and `protocol = "judostore"`
- **WHEN** `getAccessUrl("abc-123")` is called
- **THEN** the returned URL is `"judostore:abc-123-report.pdf"`

### Requirement: FileEntity Construction

`FileEntity.createEntity(String, String, InputStream)` SHALL generate a `UUID.randomUUID().toString()` as `fileId`, set `createTime` to the current `Timestamp`, and compute `size` from `InputStream.available()`.

#### Scenario: FileEntity captures metadata correctly
- **GIVEN** an `InputStream` with `available() = 1024`
- **WHEN** `FileEntity.createEntity("doc.pdf", "application/pdf", inputStream)` is called
- **THEN** the resulting entity has a non-null UUID `fileId`, `size = 1024`, `createTime` approximately equal to current time, `filename = "doc.pdf"`, and `mimeType = "application/pdf"`

### Requirement: Prepared Statement Binding

`FilestorePreparedStatementCallback.setValues(PreparedStatement, LobCreator)` SHALL bind `FileEntity` fields to the INSERT statement in order: `FILE_ID` (String, pos 1), `FILENAME` (String, pos 2), `MIME_TYPE` (String, pos 3), `SIZE` (long, pos 4), `CREATE_TIME` (Timestamp, pos 5), `DATA` (BlobAsBinaryStream, pos 6).

#### Scenario: All six parameters are bound
- **GIVEN** a `FileEntity` with `fileId = "x"`, `filename = "f.txt"`, `mimeType = "text/plain"`, `size = 100`, `createTime = <ts>`, `data = <is>`
- **WHEN** `setValues(ps, lobCreator)` is called
- **THEN** `ps.setString(1, "x")`, `ps.setString(2, "f.txt")`, `ps.setString(3, "text/plain")`, `ps.setLong(4, 100)`, `ps.setTimestamp(5, <ts>)`, and `lobCreator.setBlobAsBinaryStream(ps, 6, <is>, 100)` are invoked

### Requirement: OSGi Lifecycle Management

The service SHALL register a `URLStreamHandlerService` on activation and unregister it on deactivation.

#### Scenario: Component activation
- **GIVEN** the OSGi container provides a `BundleContext` and valid `Config` with `protocol = "judostore"` and `table = "FILESTORE"`
- **WHEN** `activate(BundleContext, Config)` is called
- **THEN** `protocol` is set to `"judostore"`, `table` is set to `"FILESTORE"`, a `JdbcTemplate` is created from the injected `DataSource`, and a `FileStoreUrlStreamHandler` is registered as `URLStreamHandlerService` with `url.handler.protocol = "judostore"`

#### Scenario: Component deactivation
- **GIVEN** the component was previously activated
- **WHEN** `deactivate()` is called
- **THEN** the `URLStreamHandlerService` registration is unregistered and the reference is set to `null`
