# Handover: Google Cloud Storage as Filestore — what to review

**Branch:** `feature/JNG-XXXX_GcsS3InteropIntegrationTest` (pushed, no PR yet)
**Commits:** `c798ac4` (GCS support + multipart fix), `4ea1913` (status report)
**Main document:** [`GCS_FILESTORE_REPORT.md`](GCS_FILESTORE_REPORT.md)

---

## 1. Reading order (about 30 min)

1. `GCS_FILESTORE_REPORT.md` §1 Summary and §7 Bottom line (5 min)
2. `GCS_FILESTORE_REPORT.md` §4 Benefits and drawbacks, §5 Security (15 min)
3. `filestore-s3/SECURITY.md` §4 Hardening checklist (5 min)
4. Optional deep dive: `filestore-s3/GCS_ASSESSMENT.md`, `GCS_INTEROP.md`, `CONFIGURATION.md`

## 2. Decisions needed from you

| # | Question | Why it matters |
|---|---|---|
| D1 | **Does our security policy allow static, long-lived cloud keys (GCS HMAC keys)?** | The key question. **Yes:** this solution works as is. **No:** we need a native GCS backend with keyless workload identity, which is new development. |
| D2 | Why GCS: are we or the customer on Google Cloud, is it about cost, or is it a customer requirement? | The report does not compare GCS with AWS S3, filesystem or RDBMS. That comparison needs this context. |
| D3 | Do existing installations need their files migrated from filesystem or RDBMS? | Not covered. It would be a separate piece of work. |
| D4 | Is it acceptable that downloads stream through Karaf (no direct links to the bucket)? | Affects bandwidth and CPU on our nodes. Presigned URLs would be a separate API and security decision. |
| D5 | GDPR / data processing agreement with Google, and region (tested: `europe-west3` Frankfurt) | Compliance has not been reviewed |

## 3. Things to check or verify

### Code (about 15 min)
- [ ] Only one production method changed: `filestore-s3/src/main/java/.../S3FileStoreService.java` → `putLargeFile` (+47 / −11).
      Command: `git diff origin/develop -- filestore-s3/src/main`
- [ ] The fix is limited to two S3-spec deviations in the third-party library (report §3). Small-file upload and metadata are untouched.
- [ ] No secrets committed: `filestore-s3/.env` is gitignored, and only `.env.example` is tracked.

### Tests
- [ ] **MinIO suite in CI.** It could not run locally (Docker Hub denied `minio/minio`). It must be green in CI before merge.
- [ ] GCS live tests (12) and wire tests (9) passed locally. To reproduce, you need GCS test credentials in `filestore-s3/.env` (see `.env.example` and `GCS_INTEROP.md`), then:
      `mvn test -pl filestore-s3`
- [ ] Note: no load or concurrency test has been done.

### Security (go-live gates, must all be done before production)
- [ ] `tokenRequired=true` on upload and download servlets (default is **off**)
- [ ] Token expiry > 0 (default: **never expires**)
- [ ] CORS: explicit origin list (default: `*` **with** credentials)
- [ ] HMAC secret from a secret manager. Plaintext in config today, and jasypt is unverified.
- [ ] Dedicated service account, `objectAdmin` on **one** bucket, key rotation documented
- [ ] `https://` endpoint only

### Repository hygiene
- [ ] **17 Dependabot alerts on `develop`** (2 critical, 8 high). Not caused by this branch, but relevant to a security sign-off.

## 4. Known gaps in the report

These are not covered yet, and could be added once D1–D3 are answered:
- No cost estimate (storage, egress and operation charges at our volumes)
- No effort estimate for the go-live gates or for a native-GCS alternative
- No comparison table against AWS S3, filesystem and RDBMS
- No migration, backup/DR or monitoring plan
- Stale wording in `filestore-s3/GCS_ASSESSMENT.md`: its subtitle and the "Impact in judo-platform terms" paragraph still describe the pre-fix "< 5 MB only" state. They should be corrected.

## 5. Before merge (to do on our side)

1. Replace `JNG-XXXX` with a real JIRA ticket (branch name and commit messages)
2. Open the PR against `develop` and get the MinIO suite green in CI
3. Fix the stale wording in `GCS_ASSESSMENT.md`, and mark `s3-filestore-comparison-report.md` as historical
4. After merge: archive the OpenSpec change `add-gcs-interop-integration-test`

## 6. Recommendation

**Go, with conditions.** Technically, GCS works and the change is small and tested. The outcome depends on **D1** (are static keys allowed) and on closing the **six security gates** before production.
