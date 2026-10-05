package hu.blackbelt.osgi.filestore.servlet.utils;

/*-
 * #%L
 * Filestore servlet (file upload)
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

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

import static hu.blackbelt.osgi.filestore.servlet.Constants.ALL;

/**
 * Activation-time warnings about unsafe servlet configuration, shared by the upload and the download servlet.
 * A warning never changes request handling.
 */
@Slf4j
public final class UnsafeConfigurationWarnings {

    private UnsafeConfigurationWarnings() {
    }

    public static void check(String servletPath, boolean tokenRequired, String corsAllowOrigin, boolean corsAllowCredentials) {
        if (!tokenRequired) {
            log.warn("Filestore servlet {} is configured with tokenRequired=false, so requests are not authenticated"
                    + " when no TokenValidator is bound", servletPath);
        }
        if (corsAllowOrigin != null && Arrays.asList(corsAllowOrigin.split("\\s*,\\s*")).contains(ALL) && corsAllowCredentials) {
            log.warn("Filestore servlet {} is configured with cors.allowOrigin={} and cors.allowCredentials=true:"
                            + " credentials are not granted to wildcard origins, configure an explicit origin list for"
                            + " credentialed requests", servletPath, ALL);
        }
    }
}
