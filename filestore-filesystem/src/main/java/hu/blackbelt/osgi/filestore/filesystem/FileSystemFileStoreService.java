package hu.blackbelt.osgi.filestore.filesystem;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.io.Closeables;
import com.google.common.primitives.Longs;
import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.urlhandler.FileStoreUrlStreamHandler;
import org.apache.sling.commons.mime.MimeTypeService;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd=FileSystemFileStoreService.Config.class)
public class FileSystemFileStoreService implements FileStoreService {

    @ObjectClassDefinition()
    public @interface Config {

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
    private File targetDir = new File(DEFAULT_ROOT);

    LoadingCache<String, java.util.Properties> propertiesLoadingCache = CACHE_EXPIRE
            .build(
                    new CacheLoader<String, java.util.Properties>() {
                        public java.util.Properties load(String key) throws IOException {
                            return idToProperties(key);
                        }
                    });

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    FileStoreUrlStreamHandler urlStreamHandler;

    @Reference
    MimeTypeService mimeTypeService;

    public FileSystemFileStoreService() { }

    @Activate
    public void activate(Config config) {
        dataStorePath = config.fileSystemStoreDirectory() != null ? config.fileSystemStoreDirectory() : DEFAULT_ROOT;
        targetDir = new File(dataStorePath);
        targetDir.mkdirs();
    }

    @Override
    public String put(InputStream data, String fileName, String mimeType) throws IOException {

        String fileId = UUID.randomUUID().toString().replaceAll(MINUS, "");

        File fileDir = idToDirectory(fileId);
        fileDir.mkdirs();

        String fn = fileName;
        String mt = mimeType;

        if (Strings.isNullOrEmpty(fileName)) {
            if (!Strings.isNullOrEmpty(mimeType)) {
                fn = fileId + "." + mimeTypeService.getExtension(mimeType);
            } else {
                fn = fileId + ".bin";
            }
        }
        if (Strings.isNullOrEmpty(mimeType)) {
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
        Closeables.closeQuietly(data);

        return fileId;
    }

    @Override
    public boolean exists(String fileId) {
        Preconditions.checkArgument(fileId != null, FILE_ID_CANNOT_BE_NULL);
        try {
            return idToDataFile(fileId).exists();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public InputStream get(String fileId) throws IOException {
        Preconditions.checkArgument(fileId != null, FILE_ID_CANNOT_BE_NULL);
        return Files.newInputStream(idToDataFile(fileId).toPath());
    }

    @Override
    public String getMimeType(String fileId) throws IOException {
        Preconditions.checkArgument(fileId != null, FILE_ID_CANNOT_BE_NULL);
        try {
            return (String) propertiesLoadingCache.get(fileId).get(MIME_TYPE);
        } catch (ExecutionException e) {
            throw new IOException(COULD_NOT_GET_PROPERTIES_FOR + fileId);
        }
    }

    @Override
    public String getFileName(String fileId) throws IOException {
        Preconditions.checkArgument(fileId != null, FILE_ID_CANNOT_BE_NULL);
        try {
            return (String) propertiesLoadingCache.get(fileId).get(FILE_NAME);
        } catch (ExecutionException e) {
            throw new IOException(COULD_NOT_GET_PROPERTIES_FOR + fileId);
        }
    }

    @Override
    public long getSize(String fileId) throws IOException {
        Preconditions.checkArgument(fileId != null, FILE_ID_CANNOT_BE_NULL);
        try {
            return Longs.tryParse((String) propertiesLoadingCache.get(fileId).get(SIZE));
        } catch (ExecutionException e) {
            throw new IOException(COULD_NOT_GET_PROPERTIES_FOR + fileId);
        }
    }

    @Override
    public LocalDateTime getCreateTime(String fileId) throws IOException {
        Preconditions.checkArgument(fileId != null, FILE_ID_CANNOT_BE_NULL);
        try {
            return LocalDateTime.parse((String) propertiesLoadingCache.get(fileId).get(CREATE_DATE));
        } catch (ExecutionException e) {
            throw new IOException(COULD_NOT_GET_PROPERTIES_FOR + fileId);
        }
    }

    @Override
    public URL getAccessUrl(String fileId) throws IOException {
        Preconditions.checkArgument(fileId != null, FILE_ID_CANNOT_BE_NULL);
        return new URL(urlStreamHandler.getProtocol() + ":" + fileId + MINUS + getFileName(fileId));
        // return idToDataFile(fileId).toURI().toURL();
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
        return Joiner.on(File.separator).join(fileId.split("(?<=\\G.{2})"));
    }
}

