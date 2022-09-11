package hu.blackbelt.osgi.filestore.rdbms.helper;

/*-
 * #%L
 * JUDO framework RDBMS filestore
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

import lombok.Getter;
import lombok.Setter;
import org.springframework.jdbc.support.lob.LobHandler;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;

import static java.time.LocalDateTime.now;

@Getter
@Setter
public final class FileEntity {
    private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static FileEntity createEntity(String filename, String mimeType, InputStream data) throws IOException {
        return new FileEntity(filename, mimeType, data);
    }

    private String filename;
    private String mimeType;
    private String fileId;
    private Timestamp createTime;
    private long size;
    private InputStream data;

    private FileEntity(String filename, String mimeType, InputStream data) throws IOException {
        this.fileId = UUID.randomUUID().toString();
        this.mimeType = mimeType;
        this.filename = filename == null ? fileId + '.' + mimeType : filename;
        this.data = data;
        this.size = data.available();
        this.createTime = new Timestamp(new Date().getTime());
    }

    private FileEntity(String filename, String mimeType, String fileId, Timestamp createTime,
                       long size, InputStream data) {
        this.filename = filename;
        this.mimeType = mimeType;
        this.fileId = fileId;
        this.createTime = createTime;
        this.size = size;
        this.data = data;
    }

    public FilestorePreparedStatementCallback getCallback(LobHandler lobHandler) {
        return new FilestorePreparedStatementCallback(lobHandler, this);
    }
}
