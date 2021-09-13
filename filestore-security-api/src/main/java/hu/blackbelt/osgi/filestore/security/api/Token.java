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

    public <T> T get(final C claim, final Class<T> clazz) {
        final Object value = jwtClaims.get(claim);
        if (value == null) {
            return null;
        }
        if (clazz.isAssignableFrom(value.getClass())) {
            return (T) value;
        } else {
            throw new IllegalArgumentException("Claim type mismatch");
        }
    }

    public interface Claim {

        String getJwtClaimName();

        default Object convert(String value) {
            return value;
        }
    }
}
