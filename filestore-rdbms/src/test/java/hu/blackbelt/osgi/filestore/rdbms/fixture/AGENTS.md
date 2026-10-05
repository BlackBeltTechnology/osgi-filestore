# DOX — filestore-rdbms/src/test/java/hu/blackbelt/osgi/filestore/rdbms/fixture

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `RdbmsDatasourceByClassExtension.java` | JUnit 5 extension: per-test-class datasource fixture lifecycle + parameter injection. |
| `RdbmsDatasourceFixture.java` | TestContainers datasource (PostgreSQL or YugabyteDB), Liquibase init, transaction helpers. |
| `RdbmsDatasourceSingetonExtension.java` | JUnit 5 extension: one shared datasource fixture for the whole run + parameter injection. |
| `YugabytedbSQLContainer.java` | TestContainers `JdbcDatabaseContainer` for YugabyteDB. |
