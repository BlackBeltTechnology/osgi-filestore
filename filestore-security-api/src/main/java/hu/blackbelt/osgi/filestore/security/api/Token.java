package hu.blackbelt.osgi.filestore.security.api;

import lombok.*;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

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

    public Map<String, Object> getClaims() {
        return Collections.unmodifiableMap(jwtClaims.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getJwtClaimName(), e -> e.getKey().convert(e.getValue()))));
    }

    public interface Claim {

        String getJwtClaimName();

        default Object convert(Object value) {
            return value;
        }
    }
}
