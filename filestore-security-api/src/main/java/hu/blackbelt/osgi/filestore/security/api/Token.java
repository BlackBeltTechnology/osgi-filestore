package hu.blackbelt.osgi.filestore.security.api;

import lombok.*;

import java.util.Map;

@Getter
@Builder
@EqualsAndHashCode
@ToString
public class Token<C extends Token.Claim> {

    @Singular
    private final Map<C, Object> jwtClaims;

    public <T> T get(C claim, Class<T> clazz) {
        return (T) jwtClaims.get(claim);
    }

    public interface Claim {

        String getJwtClaimName();
    }
}
