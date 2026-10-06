# DOX — filestore-security-api/src/main/java/hu/blackbelt/osgi/filestore/security/api

Files in this area. One row per source file.

| File | Purpose |
|---|---|
| `DownloadClaim.java` | Enum of JWT claims carried by a download token; `getByJwtClaimName` lookup. |
| `Token.java` | Generic claim-map holder `Token<C extends Token.Claim>` shared by upload and download tokens. |
| `TokenIssuer.java` | Contract: `createUploadToken` / `createDownloadToken` → signed token string. |
| `TokenValidator.java` | Contract: `parseUploadToken` / `parseDownloadToken` → `Token`, throws `InvalidTokenException`. |
| `UploadClaim.java` | Enum of JWT claims carried by an upload token; `getByJwtClaimName` lookup. |
