package hu.blackbelt.osgi.filestore.security.api;

/*-
 * #%L
 * JUDO framework security API for filestore
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

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
