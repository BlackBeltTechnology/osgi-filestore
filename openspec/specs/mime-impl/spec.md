# MIME Type Resolver Implementation Specification

## Purpose

Provides the default OSGi component implementation of `MimeTypeResolver` that combines URL-based regex pattern matching, Apache Sling `MimeTypeService` delegation, and a configurable fallback default MIME type.

## Architecture

- **`hu.blackbelt.osgi.filestore.mime.impl.DefaultMimeTypeResolver`** -- OSGi Declarative Services component (`@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)`) that implements `hu.blackbelt.osgi.filestore.mime.api.MimeTypeResolver`.
- **`DefaultMimeTypeResolver.Config`** -- Inner `@ObjectClassDefinition` configuration interface annotated with `@Designate`, defining:
  - `defaultMimeType()` -- Default MIME type returned when no match is found (default: `"application/pdf"`).
  - `mimeTypeByUrl()` -- Semicolon-separated list of `regex=mimeType` pairs for URL-based pattern matching (default: `".*/documentDownload.*type\\=PDF=application/pdf"`).
- **`org.apache.sling.commons.mime.MimeTypeService`** -- Injected via `@Reference`. Used as the secondary resolution strategy for extension-based MIME type lookup and for `getExtension()` delegation.
- **Resolution order in `getMimeType(String url)`**:
  1. Iterate `mimeTypeByUrlRegex` patterns; if any regex matches the URL, return the associated MIME type.
  2. Delegate to `mimeTypeService.getMimeType(url)`.
  3. If Sling returns `null`, return `defaultMimeType`.
- **`getExtension(String mimeType)`** -- Delegates directly to `mimeTypeService.getExtension(mimeType)`.
- **Configuration parsing** -- The `parseMimeTypeMap(String)` method handles escaped equals (`\\=` replaced with Unicode `\u2202`) and escaped semicolons (`\\;` replaced with Unicode `\u2203`) to allow regex patterns containing literal `=` and `;` characters. Entries are split by `;`, then each entry is split by `=` into a key-value pair compiled into `Pattern` keys.

## Requirements

### Requirement: Configuration-driven activation

The component SHALL require OSGi configuration (`ConfigurationPolicy.REQUIRE`) and activate with the provided `Config` values for `defaultMimeType` and `mimeTypeByUrl`.

#### Scenario: Component activates with default configuration

- **GIVEN** an OSGi configuration is deployed with `defaultMimeType = "application/pdf"` and `mimeTypeByUrl = ".*/documentDownload.*type\\=PDF=application/pdf"`
- **WHEN** the `DefaultMimeTypeResolver` component is activated
- **THEN** the `defaultMimeType` field is set to `"application/pdf"` and the `mimeTypeByUrlRegex` map contains one compiled `Pattern` entry

### Requirement: URL regex pattern matching takes priority

The component SHALL check all configured URL regex patterns first when resolving a MIME type. If any pattern matches the input URL, the corresponding MIME type is returned immediately without consulting `MimeTypeService`.

#### Scenario: URL matches a configured regex pattern

- **GIVEN** the configuration contains `mimeTypeByUrl = ".*/documentDownload.*type\\=PDF=application/pdf"` and the component is activated
- **WHEN** `getMimeType("https://example.com/documentDownload?type=PDF")` is called
- **THEN** `"application/pdf"` is returned without delegating to `MimeTypeService`

#### Scenario: URL does not match any regex pattern

- **GIVEN** the configuration contains a single regex that does not match `"image.png"`
- **WHEN** `getMimeType("image.png")` is called
- **THEN** resolution falls through to `MimeTypeService`

### Requirement: Delegation to Apache Sling MimeTypeService

When no URL regex pattern matches, the component SHALL delegate to the injected `MimeTypeService.getMimeType(url)` for extension-based resolution.

#### Scenario: Sling resolves a known extension

- **GIVEN** no URL regex matches and `MimeTypeService.getMimeType("report.xlsx")` returns `"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"`
- **WHEN** `getMimeType("report.xlsx")` is called
- **THEN** `"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"` is returned

### Requirement: Default MIME type fallback

When neither URL regex patterns nor `MimeTypeService` can resolve the MIME type (Sling returns `null`), the component SHALL return the configured `defaultMimeType`.

#### Scenario: Fallback to default MIME type

- **GIVEN** no URL regex matches and `MimeTypeService.getMimeType("unknownfile.xyz")` returns `null`, and `defaultMimeType` is `"application/pdf"`
- **WHEN** `getMimeType("unknownfile.xyz")` is called
- **THEN** `"application/pdf"` is returned

### Requirement: Extension resolution via delegation

The `getExtension(String mimeType)` method SHALL delegate directly to `MimeTypeService.getExtension(mimeType)` and return its result.

#### Scenario: Resolve extension for a known MIME type

- **GIVEN** `MimeTypeService.getExtension("image/jpeg")` returns `"jpg"`
- **WHEN** `getExtension("image/jpeg")` is called on `DefaultMimeTypeResolver`
- **THEN** `"jpg"` is returned

### Requirement: Escaped delimiters in configuration

The `parseMimeTypeMap(String)` method SHALL support escaped equals signs (`\\=`) and escaped semicolons (`\\;`) within regex patterns so that patterns containing literal `=` or `;` characters are parsed correctly.

#### Scenario: Parse a regex pattern containing a literal equals sign

- **GIVEN** the configuration string is `".*/download.*type\\=PDF=application/pdf"`
- **WHEN** `parseMimeTypeMap()` processes it
- **THEN** the resulting map contains a `Pattern` matching `".*/download.*type=PDF"` mapped to `"application/pdf"`

#### Scenario: Parse multiple semicolon-separated entries

- **GIVEN** the configuration string is `".*/a=text/plain;.*/b=text/html"`
- **WHEN** `parseMimeTypeMap()` processes it
- **THEN** the resulting map contains two entries: pattern `".*/a"` mapped to `"text/plain"` and pattern `".*/b"` mapped to `"text/html"`
