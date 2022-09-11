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

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

@Getter
@AllArgsConstructor
public enum DownloadClaim implements Token.Claim {

    FILE_ID("sub"),
    FILE_NAME("fileName"),
    FILE_SIZE("fileSize") {
        @Override
        public Long convert(Object value) {
            return value != null ? Double.valueOf(value.toString()).longValue() : null;
        }
    },
    FILE_MIME_TYPE("mimeType"),
    DISPOSITION("disposition"),
    CONTEXT("ctx");

    public static final String AUDIENCE = "Download";

    private String jwtClaimName;

    public static DownloadClaim getByJwtClaimName(final String jwtClaimName) {
        return Arrays.stream(values())
                .filter(c -> Objects.equals(jwtClaimName, c.jwtClaimName))
                .findFirst()
                .orElse(null);
    }
}
