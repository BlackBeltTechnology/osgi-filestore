package hu.blackbelt.osgi.filestore.rdbms.helper;

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
