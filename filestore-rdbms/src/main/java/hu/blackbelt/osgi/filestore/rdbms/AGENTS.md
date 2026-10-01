# DOX — filestore-rdbms/src/main/java/hu/blackbelt/osgi/filestore/rdbms

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `RdbmsFileStoreLiquibaseExecutor.java` | DS component that runs the Liquibase changelog on activate to create the configured FILESTORE table. |
| `RdbmsFileStoreService.java` | DS component implementing `FileStoreService` over a `DataSource` via Spring `JdbcTemplate` + LOB handler; metadata cached; registers a `FileStoreUrlStreamHandler`. |
