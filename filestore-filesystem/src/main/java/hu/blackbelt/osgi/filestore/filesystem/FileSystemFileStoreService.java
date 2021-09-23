package hu.blackbelt.osgi.filestore.filesystem;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import hu.blackbelt.osgi.filestore.api.FileStoreService;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd=FileSystemFileStoreService.Config.class)
@Slf4j
public class FileSystemFileStoreService implements FileStoreService {

    @ObjectClassDefinition()
    public @interface Config {

        @AttributeDefinition(name="Protocol", description = "Protocol of URL stream handler")
        String protocol();

        @AttributeDefinition(required = false, name = "Filesystem store directory")
        String fileSystemStoreDirectory();
    }

    public static final String DEFAULT_ROOT = System.getProperty("user.home") + "/file-store";
    public static final String FILE_NAME = "file-name";
    public static final String MIME_TYPE = "mime-type";
    public static final String CREATE_DATE = "create-date";
    public static final String SIZE = "size";
    public static final String FILE_PROPERTIES = "file.properties";
    public static final String FILE_ID_CANNOT_BE_NULL = "fileId cannot be null";
    public static final String COULD_NOT_GET_PROPERTIES_FOR = "Could not get properties for ";
    public static final int CACHE_SIZE = 10000;
    public static final CacheBuilder<Object, Object> CACHE_EXPIRE = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(10, TimeUnit.MINUTES);
    public static final String MINUS = "-";

    private String dataStorePath = DEFAULT_ROOT;
    private String protocol;
    private File targetDir = new File(DEFAULT_ROOT);

    LoadingCache<String, java.util.Properties> propertiesLoadingCache = CACHE_EXPIRE
            .build(
                    new CacheLoader<String, java.util.Properties>() {
                        public java.util.Properties load(String key) throws IOException {
                            return idToProperties(key);
                        }
                    });

    @Reference
    MimeTypeService mimeTypeService;

    private ServiceRegistration<URLStreamHandlerService> urlStreamHandlerServiceServiceRegistration;

    @Activate
    void activate(BundleContext context, Config config) {
        dataStorePath = config.fileSystemStoreDirectory() != null && !config.fileSystemStoreDirectory().trim().isEmpty() ? config.fileSystemStoreDirectory() : DEFAULT_ROOT;
        protocol = config.protocol() != null ? config.protocol().toLowerCase() : null;
        targetDir = new File(dataStorePath);
        targetDir.mkdirs();

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
    public String put(InputStream data, String fileName, String mimeType) throws IOException {

        String fileId = UUID.randomUUID().toString().replaceAll(MINUS, "");

        File fileDir = idToDirectory(fileId);
        fileDir.mkdirs();

        String fn = fileName;
        String mt = mimeType;

        if (isNullOrEmpty(fileName)) {
            if (!isNullOrEmpty(mimeType)) {
                fn = fileId + "." + mimeTypeService.getExtension(mimeType);
            } else {
                fn = fileId + ".bin";
            }
        }
        if (isNullOrEmpty(mimeType)) {
            mt = mimeTypeService.getMimeType(fn);
        }
        if (mt == null) {
            mt = "application/octet-stream";
        }

        File dataFile = new File(fileDir, fn);

        java.util.Properties properties = new java.util.Properties();
        properties.put(FILE_NAME, fn);
        properties.put(MIME_TYPE, mt);
        properties.put(CREATE_DATE, String.valueOf(System.currentTimeMillis()));
        properties.put(SIZE, Long.toString(Files.copy(data, Paths.get(dataFile.getAbsolutePath()))).toString());
        properties.store(new FileOutputStream(idToPropertyFile(fileId)), "This is generated file metainfo, do not edit");

        return fileId;
    }

    @Override
    public boolean exists(String id) {
        Objects.requireNonNull(id, FILE_ID_CANNOT_BE_NULL);
        String fileId = getStrippedId(id);
        try {
            return idToDataFile(fileId).exists();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public InputStream get(String id) throws IOException {
        Objects.requireNonNull(id, FILE_ID_CANNOT_BE_NULL);
        String fileId = getStrippedId(id);
        return Files.newInputStream(idToDataFile(fileId).toPath());
    }

    @Override
    public String getMimeType(String id) throws IOException {
        Objects.requireNonNull(id, FILE_ID_CANNOT_BE_NULL);
        String fileId = getStrippedId(id);
        try {
            return (String) propertiesLoadingCache.get(fileId).get(MIME_TYPE);
        } catch (ExecutionException e) {
            throw new IOException(COULD_NOT_GET_PROPERTIES_FOR + fileId);
        }
    }

    @Override
    public String getFileName(String id) throws IOException {
        Objects.requireNonNull(id, FILE_ID_CANNOT_BE_NULL);
        String fileId = getStrippedId(id);
        try {
            return (String) propertiesLoadingCache.get(fileId).get(FILE_NAME);
        } catch (ExecutionException e) {
            throw new IOException(COULD_NOT_GET_PROPERTIES_FOR + fileId);
        }
    }

    @Override
    public long getSize(String id) throws IOException {
        Objects.requireNonNull(id, FILE_ID_CANNOT_BE_NULL);
        String fileId = getStrippedId(id);
        try {
            return Long.parseLong((String) propertiesLoadingCache.get(fileId).get(SIZE));
        } catch (ExecutionException | NumberFormatException e) {
            throw new IOException(COULD_NOT_GET_PROPERTIES_FOR + fileId);
        }
    }

    @Override
    public Date getCreateTime(String id) throws IOException {
        Objects.requireNonNull(id, FILE_ID_CANNOT_BE_NULL);
        String fileId = getStrippedId(id);
        try {
            Long createTime = Long.parseLong((String) propertiesLoadingCache.get(fileId).get(CREATE_DATE));
            return new Date(createTime);
        } catch (ExecutionException | NumberFormatException e) {
            throw new IOException(COULD_NOT_GET_PROPERTIES_FOR + fileId);
        }
    }

    @Override
    public URL getAccessUrl(String id) throws IOException {
        Objects.requireNonNull(id, FILE_ID_CANNOT_BE_NULL);
        String fileId = getStrippedId(id);
        return new URL(protocol + ":" + fileId + MINUS + getFileName(fileId));
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    private File idToDirectory(String fileId) {
        return new File(targetDir, idToPath(fileId));
    }

    private File idToPropertyFile(String fileId) {
        return new File(idToDirectory(fileId), FILE_PROPERTIES);
    }

    private java.util.Properties idToProperties(String fileId) throws IOException {
        java.util.Properties properties = new java.util.Properties();
        properties.load(new FileInputStream(idToPropertyFile(fileId)));
        return properties;
    }

    private File idToDataFile(String fileId) throws IOException {
        return new File(idToDirectory(fileId), (String) idToProperties(fileId).get(FILE_NAME));
    }

    private String idToPath(String fileId) {
        return join(File.separator, fileId.split("(?<=\\G.{2})"));
    }

    static boolean isNullOrEmpty(String string) {
        return string == null || string.isEmpty();
    }

    static String join(String separator, String[] items) {
        StringBuilder sb = new StringBuilder();
        for (String part : items) {
            if (sb.length() != 0) {
                sb.append(separator);
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private String getStrippedId(String id) {
        String cleanedId = id;
        if (id != null && id.startsWith(getProtocol()) && id.split(":").length >= 2) {
            cleanedId = id.split(":")[1];
        }
        return cleanedId;
    }

}
