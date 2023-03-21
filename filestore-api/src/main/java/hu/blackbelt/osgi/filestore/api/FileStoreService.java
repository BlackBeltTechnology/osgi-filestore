package hu.blackbelt.osgi.filestore.api;

/*-
 * #%L
 * Filestore API
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;

/**
 * Represents a file store service.
 */
public interface FileStoreService {

    /**
     * Saves a file with the given <code>fileName</code> and <code>mimeType</code> to the data store. It returns the ID of the file which is used
     * to access.
     *
     * @param data
     * @param fileName
     * @param mimeType
     */
    String put(InputStream data, String fileName, String mimeType) throws IOException;

    /**
     * Checks the existence of a file <code>fileId</code> (If any representation exists return true).
     *
     * @param fileId
     * @throws IOException
     */
    boolean exists(String fileId);

    /**
     * Fetches the file with the given <code>fileId</code>.
     *
     * @param fileId
     * @return
     */
    InputStream get(String fileId) throws IOException;

    /**
     * Gets the mime type of the given fileId.
     *
     * @param fileId
     * @return
     * @throws IOException
     */
    String getMimeType(String fileId) throws IOException;

    /**
     * Get the file name of the given fileId.
     *
     * @param fileId
     * @return
     * @throws IOException
     */
    String getFileName(String fileId) throws IOException;


    /**
     * Get the file size of the given fileId.
     *
     * @param fileId
     * @return
     * @throws IOException
     */
    long getSize(String fileId) throws IOException;

    /**
     * Get the create time of the given fileId. Type is java.util.Date supporting for applications not supporting new date/time APIs.
     *
     * @param fileId
     * @return
     * @throws IOException
     */
    Date getCreateTime(String fileId) throws IOException;

    /**
     * Get absolute URL of file.
     *
     * @return
     * @throws IOException
     */
    URL getAccessUrl(String fileId) throws IOException;

    /**
     * Get protocol of file store service.
     *
     * @return
     */
    String getProtocol();
}
