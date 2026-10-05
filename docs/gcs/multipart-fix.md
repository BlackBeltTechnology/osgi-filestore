# Files ≥ 5 MB on GCS: the multipart defects and the fix

`put()` switches to the multipart path at `MULTIPART_THRESHOLD` (5 MB). That path used the
`Multipart` helper of `aws-lightweight-client-java` (0.1.24, inlined), which deviates from the S3
specification in two ways. Amazon S3 and MinIO tolerate both; GCS rejects both.

**GCS was not at fault.** The same 6 MB upload succeeds through the AWS CLI against the same bucket,
and a hand-driven initiate/part/complete sequence round-trips 6 MB.

---

## 1. The two defects

### Defect 1: no `Content-Length` on the initiate request

The initiate request (`POST /key?uploads`) legitimately has an empty body, but the library passes
**no body at all**. `HttpClientDefault` therefore never opens the output stream, and
`HttpURLConnection` emits no `Content-Length`. GCS requires the header on every POST:

```
ServiceException: statusCode=411:
  Error 411 (Length Required)
  POST requests require a Content-length header.
```

Google documents this: *"For initiating a multipart upload, this value is 0. Required: Yes."*
A body of `new byte[0]` (empty, **not absent**) is what produces `Content-Length: 0`.

### Defect 2: a malformed namespace on the complete document

`MultipartOutputStream` declares `xmlns="http:s3.amazonaws.com/doc/2006-03-01/"`. The `//` is
missing, so it is not a valid absolute URI. Amazon ignores it; GCS schema-validates it:

```
ServiceException: statusCode=400: MalformedCompleteMultipartUploadRequest
  element "CompleteMultipartUpload" not allowed anywhere;
  expected … xmlns:ns="http://s3.amazonaws.com/doc/2006-03-01/"
```

GCS does **not** require a namespace; it rejects only a malformed one. The un-namespaced form
`<CompleteMultipartUpload>` is accepted by GCS and MinIO (both verified) and matches the sample
request in the Amazon S3 API reference.

### Why it was never caught

The only large-file test targeted MinIO, which, like Amazon S3, tolerates both deviations. GCS is
the first strict backend this code has met. The defects were always there.

---

## 2. What does *not* fix it

| Attempt | Result |
|---|---|
| Configuration / OSGi property | None exists. `MULTIPART_THRESHOLD` is `private static final`, and neither defect is behind a library flag. |
| Upgrading the library | 0.1.25 (latest) and `master` carry byte-identical constants. Both defects are still present. |
| One-line patch via the helper's `transformCreateRequest` hook | **Tested.** It clears the 411, then the upload fails at the complete step with the 400. The hook covers only the initiate request; `MultipartOutputStream.close()` builds the complete document internally with no hook. Owning the complete step means owning the whole flow. |

---

## 3. The fix

`putLargeFile` drives initiate / upload-part / complete itself through the library's public
low-level `Request` API:

- initiate with `requestBody(new byte[0])`, which emits `Content-Length: 0`;
- the complete document built with the library's own public `Xml` builder
  (`com.github.davidmoten.aws.lw.client.xml.builder.Xml`), with no namespace.

Parts still stream through a single reused 5 MB buffer, so the memory profile is unchanged. No
threads are created; the library helper used to start an unbounded cached thread pool per upload.

Net change: one method (`putLargeFile`, 47 insertions / 11 deletions) plus a `readFully` helper.
`putSmallFile` and the metadata contract are untouched.

### Side effect: no `size` metadata on multipart uploads

`putSmallFile()` writes `x-amz-meta-size`; `putLargeFile()` does not. `getSize()` falls back to
the object's `Content-Length`, so it still returns the right value. This applies to every backend.

---

## 4. Failure handling

**Status: specified, not yet implemented** (OpenSpec `add-gcs-interop-integration-test`, tasks §7).

Owning the multipart flow also means owning its failure cases, which the library helper used to
hide. Two gaps were found in review and are specified in the
[`gcs-interop` spec](../../openspec/changes/add-gcs-interop-integration-test/specs/gcs-interop/spec.md)
("Multipart failure handling"):

1. **A 200 response can carry an error.** Amazon documents that `CompleteMultipartUpload` may
   answer HTTP 200 with an `<Error>` document. The current code decides success from the status
   alone, so `put()` could return a `fileId` for an object that does not exist. Planned fix: read
   the response with `responseAsXml()` and throw when the root element is `Error`.
2. **No abort on failure.** Today a failed ≥ 5 MB upload leaves an incomplete multipart upload:
   invisible as an object, but **billed as storage**. Planned fix: after a successful initiate, a
   failure sends a best-effort `DELETE ?uploadId=…`. The original exception is always the one
   thrown; an abort failure is attached with `addSuppressed`.

Either way, keep the `AbortIncompleteMultipartUpload` bucket lifecycle rule (7 days) as a safety
net for what code cannot cover (JVM crash, network loss during the abort). See
[setup.md §6](setup.md#6-production-bucket) and SECURITY.md S-14.

---

## 5. How it is guarded

| Test | Backend | Asserts |
|---|---|---|
| `S3FileStoreServiceMultipartWireTest.fixedInitiateSendsEmptyBodyNotAbsentBody` | none (fake transport) | initiate body is empty, not absent → `Content-Length: 0` |
| `S3FileStoreServiceMultipartWireTest.fixedCompleteDocumentIsWellFormedOrderedAndCarriesNoMalformedNamespace` | none (fake transport) | no malformed namespace, parts ascending, ETags verbatim |
| `GcsS3FileStoreServiceTest.putAndGetLargeFileViaMultipart` (+ 3-part variant) | real GCS bucket | 6 MB and 3-part round trips, byte-exact, metadata survives |
| `S3FileStoreServiceTest.testPutAndGetLargeFileMultipart` | MinIO | pre-existing, unchanged: no regression on Amazon-compatible backends |

Run against the **original** code, the wire test fails exactly the two `fixed*` tests by name and
passes the others, so it targets these two defects and nothing else.

---

## 6. Upstream

Three library defects should be reported upstream: the initiate body, the `xmlns` typo, and the
presigned-URL `x-amz-content-sha256` header ([setup.md §7.5](setup.md#75-presigned-urls-gcs-supports-them-the-library-does-not)).
