package hu.blackbelt.osgi.filestore.servlet.exceptions;

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

import hu.blackbelt.osgi.filestore.servlet.UploadUtils;

/**
 * Exception thrown when the recuest's length exceeds the maximum.
 */
public class UploadSizeLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int actualSize;
    final int maxSize;

    public UploadSizeLimitException(long max, long actual) {
        super();
        actualSize = (int) (actual / 1024);
        maxSize = (int) (max / 1024);
    }

    @Override
    public String getLocalizedMessage() {
        return getMessage();
    }

    @Override
    public String getMessage() {
        return UploadUtils.getMessage("size_limit", actualSize, maxSize);
    }

    public int getActualSize() {
        return actualSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

}
