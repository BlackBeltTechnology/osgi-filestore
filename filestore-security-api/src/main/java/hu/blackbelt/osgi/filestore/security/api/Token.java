package hu.blackbelt.osgi.filestore.security.api;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Map;

@Getter
@Builder
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
