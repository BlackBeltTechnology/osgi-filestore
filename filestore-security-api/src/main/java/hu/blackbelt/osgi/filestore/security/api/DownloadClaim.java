package hu.blackbelt.osgi.filestore.security.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;

@Getter
@AllArgsConstructor
public enum DownloadClaim implements Token.Claim {

    FILE_ID("sub"),
    FILE_NAME("fileName"),
    FILE_SIZE("fileSize") {
        @Override
        public Object convert(Object value) {
            return value != null ? Long.parseLong(value.toString()) : null;
        }
    },
    FILE_CREATED("fileCreated") {
        @Override
        public Object convert(Object value) {
            return value != null ? OffsetDateTime.parse(value.toString()) : null;
        }
    },
    FILE_MIME_TYPE("mimeType"),
    DISPOSITION("disposition");

    public static final String AUDIENCE = "Download";

    private String jwtClaimName;

    public static DownloadClaim getByJwtClaimName(final String jwtClaimName) {
        return Arrays.stream(values())
                .filter(c -> Objects.equals(jwtClaimName, c.jwtClaimName))
                .findFirst()
                .orElse(null);
    }
}
