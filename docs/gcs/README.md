# Google Cloud Storage as the filestore

GCS runs through the existing `filestore-s3` module via GCS's S3-compatible XML API. No new module,
no new dependency.

## Verdict

| Question | Answer |
|---|---|
| Can GCS be used as our file store? | **Yes**, through `filestore-s3`. Switching provider is a config change. |
| Does it work for all file sizes? | **Yes.** Files under 5 MB needed no change. Files ≥ 5 MB needed a fix in one method (`putLargeFile`), covered by tests. |
| Was the defect in GCS? | **No.** It was in the client library; GCS is just the first strict backend to reject it. |
| Is it production-ready? | **Functionally yes; security-wise not yet.** Close the go-live gates in [SECURITY.md §4](../../filestore-s3/SECURITY.md#4-hardening-checklist) first. Most of them apply to every S3 provider, not GCS specifically. |
| Biggest GCS-specific risk | **Static HMAC keys.** The S3 API cannot use Google's keyless workload identity. |
| Recommendation | **Go, with conditions:** decision D1 below, and the go-live gates. |

## Decisions needed

| # | Question | Why it matters |
|---|---|---|
| D1 | **Does our security policy allow static, long-lived cloud keys (GCS HMAC keys)?** | **Yes:** this solution works as is. **No:** a native GCS backend with workload identity is needed, which is new development. |
| D2 | Why GCS: are we or the customer on Google Cloud, cost, or a customer requirement? | Needed before comparing GCS with AWS S3, filesystem or RDBMS. |
| D3 | Do existing installations need their files migrated from filesystem or RDBMS? | Not covered; separate work. |
| D4 | Is it acceptable that downloads stream through Karaf (no direct bucket links)? | Bandwidth and CPU on our nodes. Presigned URLs are a separate API and security decision. |
| D5 | GDPR / data processing agreement with Google, and region (tested: `europe-west3`) | Compliance has not been reviewed. |

## Not covered yet

Cost estimate at our volumes, effort estimate for the gates or a native-GCS alternative, a
comparison against AWS S3 / filesystem / RDBMS, migration, backup/DR, monitoring, and load testing.

## Documents

| Document | Read it for | Time |
|---|---|---|
| this page | verdict and decisions | 5 min |
| [assessment.md](assessment.md) | benefits, costs, what makes it production-ready | 10 min |
| [SECURITY.md](../../filestore-s3/SECURITY.md) | findings S-1…S-15 and the go-live checklist (all S3 providers) | 10 min |
| [setup.md](setup.md) | provisioning (CLI + Console), configuration, running the tests, GCS quirks | reference |
| [multipart-fix.md](multipart-fix.md) | the ≥ 5 MB defect, the fix, failure handling, guarding tests | reference |
| [CONFIGURATION.md](../../filestore-s3/CONFIGURATION.md) | every property and the silent-failure traps (all S3 providers) | reference |

Specs and design decisions: the [`gcs-interop` spec](../../openspec/specs/gcs-interop/spec.md), the
archived change
[`add-gcs-interop-integration-test`](../../openspec/changes/archive/2026-10-05-add-gcs-interop-integration-test/),
and the open change
[`harden-servlet-defaults`](../../openspec/changes/harden-servlet-defaults/).
