## 1. Test setup

- [x] 1.1 Add JUnit Jupiter, Mockito and Hamcrest (test scope, versions from the root POM) to `filestore-servlet/pom.xml`, and verify `mvn test -pl filestore-servlet` runs and reports 0 tests without errors
- [x] 1.2 Check that `filestore-security/pom.xml` already has JUnit Jupiter alongside Mockito and Hamcrest, add it if missing, and verify `mvn test -pl filestore-security` runs

## 2. CORS wildcard rule (filestore-servlet)

- [x] 2.1 Write `CorsProcessorTest` covering the "CORS Processing" scenarios: explicit-origin preflight, rejected origin, wildcard GET, wildcard preflight, explicit list keeps credentials, no `Origin` header. Verify the two wildcard tests fail against the current code (TDD red)
- [x] 2.2 Implement the wildcard rule in `CorsProcessor` (literal `*`, no `Access-Control-Allow-Credentials` when `allowOrigins` contains `*`) for both the preflight and the actual-request paths, and verify `CorsProcessorTest` is fully green
- [x] 2.3 Verify `FilestoreTest` (itest) still passes unchanged. It uses an explicit origin, so it is expected to stay green (green in the full `mvn clean install`)

## 3. Servlet activation warnings (filestore-servlet)

- [x] 3.1 Write tests for the "Unsafe Servlet Configuration Warnings" scenarios on both `UploadServlet` and `DownloadServlet` (capture the Logback output and assert the WARN entries). Verify they fail (TDD red)
- [x] 3.2 Add the two checks to each servlet's `@Activate` method through one shared helper (DRY), and verify the tests are green

## 4. Token expiry default and clock skew (filestore-security)

- [x] 4.1 Write `DefaultTokenIssuerTest` and `DefaultTokenValidatorTest` for the "Default Token Expiration" scenarios and the new "Token Validation" scenarios (within skew, beyond skew, missing `exp` while enforced). Verify the default and skew tests fail (TDD red)
- [x] 4.2 Change `TokenServiceConfig.expirationTime()` default to `1440`, update its description, and add `int allowedClockSkew() default 60` (seconds). Verify the issuer default test is green
- [x] 4.3 Apply `setAllowedClockSkewInSeconds(allowedClockSkew)` in `DefaultTokenValidator.parseToken`, and verify all validator tests are green
- [x] 4.4 Add the "Non-Expiring Token Warning" check to `DefaultTokenIssuer`'s `@Activate` method with tests for the warning and the silent case, and verify they are green

## 5. Cleartext endpoint warning (filestore-s3)

- [x] 5.1 Write tests for the "Cleartext Endpoint Warning" scenarios (`http://` warns, `https://` and empty are silent, activation still succeeds), and verify they fail (TDD red)
- [x] 5.2 Add the check to `S3FileStoreService.activate`, and verify `mvn test -pl filestore-s3` is green (MinIO suite where Docker allows it)

## 6. Integration and compatibility checks

- [x] 6.1 Run `mvn clean install` at the repo root (all modules including `filestore-itest`), and verify it is green
- [x] 6.2 Check whether `JudoDefaultSpringConfiguration` (judo-runtime-core-spring) builds `DefaultTokenIssuer`/`DefaultTokenValidator` through the DS annotation defaults. Record the answer in design.md under Open Questions and in the release notes
- [x] 6.3 Confirm no judo-ng frontend source sends credentialed cross-origin requests to the filestore servlets (`withCredentials`, `credentials: 'include'`), and record the result in the proposal Impact section. Checked the UI generator itself (`judo-tatami-typescript`): filestore calls authenticate with the `X-Token` header, app auth is an OIDC Bearer header, no `withCredentials` anywhere

## 7. Documentation and release

- [x] 7.1 Update `openspec/specs/filestore-security/spec.md` Architecture (`TokenServiceConfig` defaults, `allowedClockSkew`, the issuer WARN) and `openspec/specs/filestore-servlet/spec.md` Architecture (`CorsProcessor` wildcard rule, `UnsafeConfigurationWarnings`), verified with `openspec validate --all --strict` (both specs pass; the pre-existing `defensive-checks` and `hybrid-upload` failures are untouched by this change). The requirement bodies themselves are merged by `openspec archive`
- [x] 7.2 Update `filestore-s3/SECURITY.md` S-5, S-8 and the hardening checklist, and the defaults table in `filestore-s3/CONFIGURATION.md`, to match the new defaults
- [x] 7.3 Write release notes covering: the expiry default (24 h, the effect on forms open longer than that, `expirationTime=0` to opt out), the wildcard CORS change (an explicit origin list for credentialed clients), `allowedClockSkew`, the rolling-upgrade note, and rollback by configuration
- [ ] 7.4 Version bump deferred by decision (2026-10-05): the root `pom.xml` `revision` stays `1.3.1-SNAPSHOT`. The bump to the next minor version is left to the release process, not to this change. (Verified earlier that a bump to `1.4.0-SNAPSHOT` builds green, then reverted.)

## 8. judo-platform template

- [x] 8.1 Change the dispatcher config template default to `filestoreTokenExpiry = ${filestoreTokenExpiry!"1440"}` (judo-platform, `judo-platform-config-templates`, branch `feature/JNG-6418_FilestoreTokenExpiryDefault`). Verified by rendering the template with FreeMarker: unset → `1440`, `0` → `0`, `60` → `60`; `develop` renders `0`
- [ ] 8.2 **Release gate, not a code task — stays open until the release.** Release in dependency order: osgi-filestore first, then bump `osgi-filestore-version` in `judo-runtime-core/pom.xml:77`, then judo-platform (`pom.xml:46`) carrying the template change. The template must not ship before the filestore version, so `1440` only ever reaches a validator that applies `allowedClockSkew`
- [x] 8.3 In the release notes, name the variables: `JUDO_PLATFORM_FILESTORE_TOKEN_EXPIRY` (expiry, `0` = never) and `JUDO_PLATFORM_CORS_ALLOW_ORIGIN` (explicit origin list for credentialed clients, silences the CORS WARN)
