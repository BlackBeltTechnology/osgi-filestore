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

    public Object get(final C claim) {
        final Object value = jwtClaims.get(claim);
        if (value == null) {
            return null;
        }
        return claim.convert(value);
    }

    public interface Claim {

        String getJwtClaimName();

        default Object convert(Object value) {
            return value;
        }
    }
}
