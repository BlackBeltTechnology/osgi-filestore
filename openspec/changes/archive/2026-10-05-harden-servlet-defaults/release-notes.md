# osgi-filestore (next minor) — safer servlet and token defaults

This is a **minor** release: nothing is removed or renamed, but two defaults change and four
activation warnings are new. Everything here can be reverted by configuration alone — no data is
migrated.

## 1. Upload and download tokens expire after 24 hours

`TokenServiceConfig.expirationTime` now defaults to **`1440` minutes (24 h)** instead of `0`
("never expires"). The issuer sets `exp` on every token, and the validator requires one.

- **Visible effect:** a form or browser tab kept open for more than 24 hours fails to save with
  `ERROR_INVALID_FILE_TOKEN`. Reloading the page fixes it.
- **Keeping the old behaviour:** set `expirationTime = 0`. Tokens then never expire and the issuer
  logs one WARN per activation.
- **judo-platform:** the dispatcher config template default changed in the same way
  (`${filestoreTokenExpiry!"1440"}`). Override it with the existing environment variable
  **`JUDO_PLATFORM_FILESTORE_TOKEN_EXPIRY`** (minutes; `0` = never expires). **No new environment
  variable is introduced.** Release the judo-platform template change together with this version,
  not before it — a `1440` default reaching an older validator means no clock-skew tolerance.
- **Spring (judo-runtime-core-spring):** unaffected. `JudoDefaultSpringConfiguration` only consumes
  optional `TokenIssuer` / `TokenValidator` beans; it never constructs `DefaultTokenIssuer` or
  `DefaultTokenValidator`, so these DS annotation defaults apply to Karaf / judo-platform
  deployments only.

### Rolling upgrade

Upgraded validators reject tokens that carry no `exp` claim, so tokens issued by not-yet-upgraded
nodes, or held by tabs opened before the upgrade, fail for the duration of the rollout. The window
is one session. Either upgrade outside working hours, or set `expirationTime = 0` for the rollout
and remove it afterwards.

## 2. New: `allowedClockSkew`

`TokenServiceConfig.allowedClockSkew`, default **`60` seconds**, is the clock difference the
validator tolerates on the `exp` and `nbf` claims. Without it, nodes whose clocks differ slightly
would reject each other's tokens around the expiry moment. The issuer ignores the setting.

On judo-platform the value is not templated, so platform apps always get the 60 s default.

## 3. A wildcard CORS origin no longer grants credentials

When `cors.allowOrigin` contains `*`, both servlets now reply with the literal
`Access-Control-Allow-Origin: *`, never echo the request's `Origin`, and **omit**
`Access-Control-Allow-Credentials` whatever `cors.allowCredentials` says.

- **Affected:** only cross-origin requests sent with `credentials: 'include'` / `withCredentials`
  against a wildcard-configured servlet. Same-origin requests, server-to-server calls, and servlets
  configured with an explicit origin list behave exactly as before.
- **Fix for credentialed clients:** configure an explicit, comma-separated origin list. On
  judo-platform the variable is **`JUDO_PLATFORM_CORS_ALLOW_ORIGIN`**; setting it also silences the
  new WARN.

## 4. New activation warnings (WARN, once per activation)

None of these change request handling.

| component | warns when |
|---|---|
| `UploadServlet`, `DownloadServlet` | `tokenRequired = false` |
| `UploadServlet`, `DownloadServlet` | `cors.allowOrigin` contains `*` while `cors.allowCredentials = true` |
| `DefaultTokenIssuer` | `expirationTime = 0` |
| `S3FileStoreService` | `endpoint` starts with `http://` |

A judo-platform app that sets nothing gets one wildcard-CORS WARN per servlet; set
`JUDO_PLATFORM_CORS_ALLOW_ORIGIN` to silence it.

## 5. Rollback

Configuration only:

- `expirationTime = 0` restores non-expiring tokens.
- An explicit `cors.allowOrigin` list restores credentialed cross-origin requests.

Nothing is persisted or migrated, so a downgrade needs no data changes.
