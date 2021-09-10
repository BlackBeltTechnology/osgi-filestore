package hu.blackbelt.osgi.filestore.security.api;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

@Getter
@AllArgsConstructor
public enum DownloadClaim implements Token.Claim {

    FILE_ID("fileId"),
    FILE_NAME("fileName"),
    FILE_SIZE("fileSize"),
    FILE_CREATED("fileCreated"),
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
