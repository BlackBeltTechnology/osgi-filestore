## ADDED Requirements

### Requirement: Cleartext Endpoint Warning

On activation, when the configured `endpoint` starts with `http://` (case-insensitive), the S3 filestore SHALL log at WARN level, once per activation, that credentials-signed requests and file content travel unencrypted. The warning SHALL NOT prevent activation or change request handling.

#### Scenario: HTTP endpoint configured
- **GIVEN** a `Config` with `endpoint = "http://minio.local:9000"`
- **WHEN** the component is activated
- **THEN** one WARN entry is logged naming the endpoint
- **AND** the component activates and serves requests as before

#### Scenario: HTTPS or default endpoint is silent
- **GIVEN** a `Config` with `endpoint = "https://storage.googleapis.com"`, or with no endpoint so the AWS default is used
- **WHEN** the component is activated
- **THEN** no WARN entry about the endpoint is logged
