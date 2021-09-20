package hu.blackbelt.osgi.filestore.security.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

@Getter
@AllArgsConstructor
public enum UploadClaim implements Token.Claim {

    FILE_MIME_TYPE_LIST("mimeTypeList"),
    MAX_FILE_SIZE("maxFileSize") {
        @Override
        public Long convert(Object value) {
            return value != null ? Long.parseLong(value.toString()) : null;
        }
    },
    CONTEXT("ctx");

    public static final String AUDIENCE = "Upload";

    private String jwtClaimName;

    public static UploadClaim getByJwtClaimName(final String jwtClaimName) {
        return Arrays.stream(values())
                .filter(c -> Objects.equals(jwtClaimName, c.jwtClaimName))
                .findFirst()
                .orElse(null);
    }
}
