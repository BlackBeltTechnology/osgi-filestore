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
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.osgi.service.url.URLStreamHandlerService;

import java.net.URL;
import java.net.URLConnection;

public class FileStoreUrlStreamHandler extends AbstractURLStreamHandlerService implements URLStreamHandlerService {

    private final FileStoreService fileStoreService;

    public FileStoreUrlStreamHandler(FileStoreService fileStoreService) {
        this.fileStoreService = fileStoreService;
    }

    @Override
    public URLConnection openConnection(URL url) {
        String uuid = url.toString().substring((fileStoreService.getProtocol() + ":").length(), url.toString().indexOf("-"));
        return new FileStoreUrlConnection(url, fileStoreService, uuid);
    }
}
