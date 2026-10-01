# DOX — filestore-servlet/src/main/java/hu/blackbelt/osgi/filestore/servlet/exceptions

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `MissingParameterException.java` | Checked: required request parameter absent. |
| `TokenRequiredException.java` | Checked: token enforcement on but no token supplied. |
| `UploadActionException.java` | Runtime: failure inside `UploadAction.executeAction`. |
| `UploadCanceledException.java` | Runtime: upload cancelled by the client. |
| `UploadException.java` | Runtime: generic upload failure wrapper. |
| `UploadSizeLimitException.java` | Runtime: request exceeds configured max size (carries max/actual). |
| `UploadTimeoutException.java` | Runtime: no data received within the listener timeout. |
