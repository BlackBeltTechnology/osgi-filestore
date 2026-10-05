# DOX — filestore-rdbms/src/main/java/hu/blackbelt/osgi/filestore/rdbms/helper

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `FileEntity.java` | Immutable row model (id, filename, mime type, create time, data stream); `createEntity` + `getCallback` build the insert callback. |
| `FilestoreHelper.java` | Static SQL builders (`count`, `read`, `meta`) parameterised by table name. |
| `FilestorePreparedStatementCallback.java` | Spring `AbstractLobCreatingPreparedStatementCallback` that binds a `FileEntity` (incl. BLOB stream) to the insert statement. |
