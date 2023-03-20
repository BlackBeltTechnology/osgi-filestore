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

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.support.AbstractLobCreatingPreparedStatementCallback;
import org.springframework.jdbc.support.lob.LobCreator;
import org.springframework.jdbc.support.lob.LobHandler;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public class FilestorePreparedStatementCallback extends AbstractLobCreatingPreparedStatementCallback {
    private final FileEntity fileEntity;

    public FilestorePreparedStatementCallback(LobHandler lobHandler, FileEntity fileEntity) {
        super(lobHandler);
        this.fileEntity = fileEntity;
    }

    /**
     * Inserts value to parameters.
     *
     *    INSERT INTO JUDO_FILESTORE(FILE_ID, FILENAME, MIME_TYPE, SIZE, CREATE_TIME, DATA)
     *    VALUES(?, ?, ?, ?, ?, ?);
     */
    @Override
    protected void setValues(PreparedStatement ps, LobCreator lobCreator) throws SQLException, DataAccessException {
        ps.setString(1, fileEntity.getFileId());
        ps.setString(2, fileEntity.getFilename());
        ps.setString(3, fileEntity.getMimeType());
        ps.setLong(4, fileEntity.getSize());
        ps.setTimestamp(5, fileEntity.getCreateTime());
        lobCreator.setBlobAsBinaryStream(ps, 6, fileEntity.getData(), (int) fileEntity.getSize());
    }
}
