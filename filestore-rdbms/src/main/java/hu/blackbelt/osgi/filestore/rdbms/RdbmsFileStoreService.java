package hu.blackbelt.osgi.filestore.rdbms;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.rdbms.helper.FileEntity;
import hu.blackbelt.osgi.filestore.rdbms.helper.FilestoreHelper;
import hu.blackbelt.osgi.filestore.urlhandler.FileStoreUrlStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.sling.commons.mime.MimeTypeService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.url.URLStreamHandlerService;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.lob.DefaultLobHandler;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.Timestamp;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static hu.blackbelt.osgi.filestore.rdbms.helper.FilestoreHelper.*;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = RdbmsFileStoreService.Config.class)
@Slf4j
public class RdbmsFileStoreService implements FileStoreService {

    @ObjectClassDefinition()
    public @interface Config {

        @AttributeDefinition(name="Protocol", description = "Protocol of URL stream handler")
        String protocol();

        @AttributeDefinition(name="Table", description = "Table to store files")
        String table() default "FILESTORE";
    }

    @Reference
    DataSource dataSource;

    @Reference
    MimeTypeService mimeTypeService;

    JdbcTemplate jdbcTemplate;


    public static final String COULD_NOT_GET_PROPERTIES_FOR = "Could not get properties for ";

    public static final int CACHE_SIZE = 10000;
    public static final CacheBuilder<Object, Object> CACHE_EXPIRE = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(10, TimeUnit.MINUTES);

    LoadingCache<String, Map<String, ?>> metaCache = CACHE_EXPIRE
            .build(
                    new CacheLoader<String, Map<String, ?>>() {
                        public Map<String, ?> load(String fileId) throws IOException {
                            return getMeta(fileId);
                        }
                    });


    private ServiceRegistration<URLStreamHandlerService> urlStreamHandlerServiceServiceRegistration;

    private final DefaultLobHandler lobHandler = new DefaultLobHandler();

    private String protocol;

    private String table;

    @Activate
    void activate(BundleContext context, Config config) {
        protocol = config.protocol() != null ? config.protocol().toLowerCase() : null;
        table = config.table();
        jdbcTemplate = new JdbcTemplate(dataSource);

        Dictionary props = new Hashtable();
        props.put("url.handler.protocol", protocol);
        urlStreamHandlerServiceServiceRegistration = context.registerService(URLStreamHandlerService.class, new FileStoreUrlStreamHandler(this), props);
    }

    @Deactivate
    void deactivate() {
        if (urlStreamHandlerServiceServiceRegistration != null) {
            urlStreamHandlerServiceServiceRegistration.unregister();
        }
        urlStreamHandlerServiceServiceRegistration = null;
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

        jdbcTemplate.execute(FilestoreHelper.insert(table), file.getCallback(lobHandler));
        return file.getFileId();
    }

    @Override
    public boolean exists(String id) {
        String fileId = getStrippedId(id);
        Integer count = jdbcTemplate.queryForObject(count(table, fileId), Integer.class);
        return count == 1;
    }

    @Override
    public InputStream get(String id) {
        String fileId = getStrippedId(id);
        return jdbcTemplate.queryForObject(read(table, fileId, DATA_FIELD),
                (rs, rowNum) -> rs.getBinaryStream(DATA_FIELD));
    }

    @Override
    public String getMimeType(String id) {
        String fileId = getStrippedId(id);
        try {
            return (String) metaCache.get(fileId).get(MIME_TYPE_FIELD);
        } catch (NullPointerException | UncheckedExecutionException | IncorrectResultSizeDataAccessException | ExecutionException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public String getFileName(String id) {
        String fileId = getStrippedId(id);
        try {
            return (String) metaCache.get(fileId).get(FILENAME_FIELD);
        } catch (NullPointerException | UncheckedExecutionException | IncorrectResultSizeDataAccessException | ExecutionException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public long getSize(String id) {
        String fileId = getStrippedId(id);
        try {
            return (Long) metaCache.get(fileId).get(SIZE_FIELD);
        } catch (NullPointerException | UncheckedExecutionException | IncorrectResultSizeDataAccessException | ExecutionException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public Timestamp getCreateTime(String id) {
        String fileId = getStrippedId(id);
        try {
            return (Timestamp) metaCache.get(fileId).get(CREATE_TIME_FIELD);
        } catch (NullPointerException | UncheckedExecutionException | IncorrectResultSizeDataAccessException | ExecutionException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    private Map<String, ?> getMeta(String id) {
        String fileId = getStrippedId(id);
        return jdbcTemplate.queryForObject(meta(table, fileId),
                (RowMapper<Map<String, ?>>) (rs, rowNum) -> ImmutableMap.of(
                    FILENAME_FIELD, rs.getString(FILENAME_FIELD),
                    MIME_TYPE_FIELD, rs.getString(MIME_TYPE_FIELD),
                    CREATE_TIME_FIELD, rs.getTimestamp(CREATE_TIME_FIELD),
                    SIZE_FIELD, rs.getLong(SIZE_FIELD)));

    }

    @Override
    public URL getAccessUrl(String id) throws IOException {
        String fileId = getStrippedId(id);
        return new URL(protocol + ":" + fileId + '-' + getFileName(fileId));
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    static boolean isNullOrEmpty(String string) {
        return string == null || string.length() == 0;
    }

    private String getStrippedId(String id) {
        String cleanedId = id;
        if (id != null && id.startsWith(getProtocol()) && id.split(":").length >= 2) {
            cleanedId = id.split(":")[1];
        }
        return cleanedId;
    }

}
