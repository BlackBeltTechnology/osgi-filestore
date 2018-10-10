package hu.blackbelt.osgi.filestore.rdbms;

import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.rdbms.helper.FileEntity;
import hu.blackbelt.osgi.filestore.urlhandler.FileStoreUrlStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.sling.commons.mime.MimeTypeService;
import org.osgi.service.component.annotations.*;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.lob.DefaultLobHandler;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import static hu.blackbelt.osgi.filestore.rdbms.helper.FilestoreHelper.*;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Slf4j
public class RdbmsFileStoreService implements FileStoreService {

    @Reference
    DataSource dataSource;

    @Reference
    MimeTypeService mimeTypeService;

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    FileStoreUrlStreamHandler urlStreamHandler;

    private final DefaultLobHandler lobHandler = new DefaultLobHandler();

    private JdbcTemplate jdbcTemplate;

    @Activate
    public void activate() {
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public final String put(final InputStream data, final String fileName, final String mimeType) throws IOException {
        FileEntity file = FileEntity.createEntity(fileName, mimeType, data);

        if (isNullOrEmpty(fileName)) {
            if (!isNullOrEmpty(mimeType)) {
                file.setFilename(file.getFileId() + "." + mimeTypeService.getExtension(mimeType));
            } else {
                file.setFilename(file.getFileId() + ".bin");
            }
        }
        if (isNullOrEmpty(mimeType)) {
            file.setMimeType(mimeTypeService.getMimeType(file.getFilename()));
        }
        if (file.getMimeType() == null) {
            file.setMimeType("application/octet-stream");
        }

        jdbcTemplate.execute(INSERT_INTO, file.getCallback(lobHandler));
        return file.getFileId();
    }

    @Override
    public boolean exists(String fileId) {
        Integer count = jdbcTemplate.queryForObject(countString(fileId), Integer.class);
        return count == 1;
    }

    @Override
    public InputStream get(String fileId) {
        try {
            return jdbcTemplate.queryForObject(readString(fileId, DATA_FIELD), new RowMapper<InputStream>() {
                @Override
                public InputStream mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return rs.getBinaryStream(DATA_FIELD);
                }
            });
        } catch (IncorrectResultSizeDataAccessException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public String getMimeType(String fileId) {
        return jdbcTemplate.queryForObject(readString(fileId, MIME_TYPE_FIELD), new RowMapper<String>() {
            @Override
            public String mapRow(ResultSet rs, int rowNum) throws SQLException {
                return rs.getString(MIME_TYPE_FIELD);
            }
        });
    }

    @Override
    public String getFileName(String fileId) {
        try {
            return jdbcTemplate.queryForObject(readString(fileId, FILENAME_FIELD), new RowMapper<String>() {
                @Override
                public String mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return rs.getString(FILENAME_FIELD);
                }
            });
        } catch (IncorrectResultSizeDataAccessException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public long getSize(String fileId) {
        try {
            return jdbcTemplate.queryForObject(readString(fileId, SIZE_FIELD), new RowMapper<Long>() {
                @Override
                public Long mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return rs.getLong(SIZE_FIELD);
                }
            });
        } catch (IncorrectResultSizeDataAccessException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public Timestamp getCreateTime(String fileId) {
        try {
            Timestamp createTimeDate = jdbcTemplate.queryForObject(
                    readString(fileId, CREATE_TIME_FIELD), new RowMapper<Timestamp>() {
                        @Override
                        public Timestamp mapRow(ResultSet rs, int rowNum) throws SQLException {
                            return rs.getTimestamp(CREATE_TIME_FIELD);
                        }
                    });
            return createTimeDate;
        } catch (IncorrectResultSizeDataAccessException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public URL getAccessUrl(String fileId) throws IOException {
        return new URL(urlStreamHandler.getProtocol() + ":" + fileId + '-' + getFileName(fileId));
    }

    static boolean isNullOrEmpty(String string) {
        return string == null || string.length() == 0;
    }
}
