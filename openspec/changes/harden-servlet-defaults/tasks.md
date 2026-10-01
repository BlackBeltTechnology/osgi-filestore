## 1. Test setup

- [ ] 1.1 Add JUnit Jupiter, Mockito and Hamcrest (test scope, versions from the root POM) to `filestore-servlet/pom.xml`, and verify `mvn test -pl filestore-servlet` runs and reports 0 tests without errors
- [ ] 1.2 Check that `filestore-security/pom.xml` already has JUnit Jupiter alongside Mockito and Hamcrest, add it if missing, and verify `mvn test -pl filestore-security` runs

## 2. CORS wildcard rule (filestore-servlet)

- [ ] 2.1 Write `CorsProcessorTest` covering the "CORS Processing" scenarios: explicit-origin preflight, rejected origin, wildcard GET, wildcard preflight, explicit list keeps credentials, no `Origin` header. Verify the two wildcard tests fail against the current code (TDD red)
- [ ] 2.2 Implement the wildcard rule in `CorsProcessor` (literal `*`, no `Access-Control-Allow-Credentials` when `allowOrigins` contains `*`) for both the preflight and the actual-request paths, and verify `CorsProcessorTest` is fully green
- [ ] 2.3 Verify `FilestoreTest` (itest) still passes unchanged. It uses an explicit origin, so it is expected to stay green

## 3. Servlet activation warnings (filestore-servlet)

- [ ] 3.1 Write tests for the "Unsafe Servlet Configuration Warnings" scenarios on both `UploadServlet` and `DownloadServlet` (capture the Logback output and assert the WARN entries). Verify they fail (TDD red)
- [ ] 3.2 Add the two checks to each servlet's `@Activate` method through one shared helper (DRY), and verify the tests are green

## 4. Token expiry default and clock skew (filestore-security)

- [ ] 4.1 Write `DefaultTokenIssuerTest` and `DefaultTokenValidatorTest` for the "Default Token Expiration" scenarios and the new "Token Validation" scenarios (within skew, beyond skew, missing `exp` while enforced). Verify the default and skew tests fail (TDD red)
- [ ] 4.2 Change `TokenServiceConfig.expirationTime()` default to `1440`, update its description, and add `int allowedClockSkew() default 60` (seconds). Verify the issuer default test is green
- [ ] 4.3 Apply `setAllowedClockSkewInSeconds(allowedClockSkew)` in `DefaultTokenValidator.parseToken`, and verify all validator tests are green
- [ ] 4.4 Add the "Non-Expiring Token Warning" check to `DefaultTokenIssuer`'s `@Activate` method with tests for the warning and the silent case, and verify they are green

## 5. Cleartext endpoint warning (filestore-s3)

- [ ] 5.1 Write tests for the "Cleartext Endpoint Warning" scenarios (`http://` warns, `https://` and empty are silent, activation still succeeds), and verify they fail (TDD red)
- [ ] 5.2 Add the check to `S3FileStoreService.activate`, and verify `mvn test -pl filestore-s3` is green (MinIO suite where Docker allows it)

## 6. Integration and compatibility checks

- [ ] 6.1 Run `mvn clean install` at the repo root (all modules including `filestore-itest`), and verify it is green
- [ ] 6.2 Check whether `JudoDefaultSpringConfiguration` (judo-runtime-core-spring) builds `DefaultTokenIssuer`/`DefaultTokenValidator` through the DS annotation defaults. Record the answer in design.md under Open Questions and in the release notes
- [ ] 6.3 Confirm no judo-ng frontend source sends credentialed cross-origin requests to the filestore servlets (`withCredentials`, `credentials: 'include'`), and record the result in the proposal Impact section

## 7. Documentation and release

- [ ] 7.1 Update `openspec/specs/filestore-security/spec.md` Architecture (`TokenServiceConfig` defaults, `allowedClockSkew`) and `openspec/specs/filestore-servlet/spec.md` Architecture (`CorsProcessor` wildcard rule) when the change is archived, and verify with `openspec validate --strict`
- [ ] 7.2 Update `filestore-s3/SECURITY.md` S-5, S-8 and the hardening checklist, and the defaults table in `filestore-s3/CONFIGURATION.md`, to match the new defaults
- [ ] 7.3 Write release notes covering: the expiry default (24 h, the effect on forms open longer than that, `expirationTime=0` to opt out), the wildcard CORS change (an explicit origin list for credentialed clients), `allowedClockSkew`, the rolling-upgrade note, and rollback by configuration
- [ ] 7.4 Bump `revision` in the root `pom.xml` to the next minor version, and verify the build picks it up
