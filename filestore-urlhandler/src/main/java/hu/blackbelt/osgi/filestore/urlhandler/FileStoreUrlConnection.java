package hu.blackbelt.osgi.filestore.urlhandler;

/*-
 * #%L
 * URL handler for filestore
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

import hu.blackbelt.osgi.filestore.api.FileStoreService;
import lombok.SneakyThrows;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

public class FileStoreUrlConnection extends URLConnection {

    final FileStoreService fileStoreService;
    final String uuid;

    protected FileStoreUrlConnection(URL url, FileStoreService fileStoreService, String uuid) {
        super(url);
        this.fileStoreService = fileStoreService;
        this.uuid = uuid;
    }

    @Override
    public void connect() {
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return fileStoreService.get(uuid);
    }

    @Override
    @SneakyThrows(IOException.class)
    public int getContentLength() {
        fileStoreService.getSize(uuid);
        return super.getContentLength();
    }

    @Override
    @SneakyThrows(IOException.class)
    public String getContentType() {
        return fileStoreService.getMimeType(uuid);
    }

    @Override
    @SneakyThrows(IOException.class)
    public long getDate() {
        return fileStoreService.getCreateTime(uuid).getTime();
    }
}
