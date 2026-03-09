# MIME Type Resolver API Specification

## Purpose

Defines the contract for bidirectional mapping between file names (or URLs) and MIME types, allowing callers to resolve a MIME type from a file name extension and vice versa.

## Architecture

- **`hu.blackbelt.osgi.filestore.mime.api.MimeTypeResolver`** -- Single interface in the `mime-api` module. It declares two methods:
  - `String getMimeType(String url)` -- Resolves a MIME type from a URL or file name. The extension is the part after the last dot; if there is no dot, the entire string is treated as the extension. Returns `null` if the input is `null` or the extension cannot be mapped.
  - `String getExtension(String mimeType)` -- Returns the primary file extension for a given MIME type. The contract guarantees the round-trip identity: `mimeType.equals(getMimeType(getExtension(mimeType)))` must hold for any non-null MIME type that has a mapping.
- This is a pure API module with no implementation classes; implementations are provided by separate bundles (e.g. `mime-impl`).

## Requirements

### Requirement: MIME type resolution from file name or URL

The resolver SHALL return the correct MIME type for a given file name or URL string by examining the file extension (the substring after the last dot).

#### Scenario: Resolve MIME type from a simple file name

- **GIVEN** a `MimeTypeResolver` implementation is available
- **WHEN** `getMimeType("document.pdf")` is called
- **THEN** the string `"application/pdf"` is returned

#### Scenario: Resolve MIME type from a URL with path

- **GIVEN** a `MimeTypeResolver` implementation is available
- **WHEN** `getMimeType("https://example.com/files/image.png")` is called
- **THEN** the string `"image/png"` is returned

#### Scenario: Resolve MIME type for a name without a dot

- **GIVEN** a `MimeTypeResolver` implementation is available
- **WHEN** `getMimeType("pdf")` is called (no dot, entire string treated as extension)
- **THEN** the string `"application/pdf"` is returned

#### Scenario: Return null for null input

- **GIVEN** a `MimeTypeResolver` implementation is available
- **WHEN** `getMimeType(null)` is called
- **THEN** `null` is returned

### Requirement: Extension resolution from MIME type

The resolver SHALL return a primary file extension string for a given MIME type via `getExtension(String mimeType)`. The returned extension, when passed back to `getMimeType()`, must produce the original MIME type.

#### Scenario: Resolve extension for a known MIME type

- **GIVEN** a `MimeTypeResolver` implementation is available
- **WHEN** `getExtension("application/pdf")` is called
- **THEN** a non-null extension string (e.g. `"pdf"`) is returned

#### Scenario: Round-trip identity holds

- **GIVEN** a `MimeTypeResolver` implementation is available and `mimeType` is `"text/plain"`
- **WHEN** `getExtension("text/plain")` returns extension `ext`, and `getMimeType(ext)` is called
- **THEN** the result equals `"text/plain"`

#### Scenario: Return null for unknown MIME type

- **GIVEN** a `MimeTypeResolver` implementation is available
- **WHEN** `getExtension("application/x-nonexistent")` is called and no mapping exists
- **THEN** `null` is returned
