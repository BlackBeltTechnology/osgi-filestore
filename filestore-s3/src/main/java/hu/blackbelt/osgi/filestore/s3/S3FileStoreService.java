package hu.blackbelt.osgi.filestore.s3;

import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.api.FilenameUtils;
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
import com.github.davidmoten.aws.lw.client.Client;
import com.github.davidmoten.aws.lw.client.HttpMethod;
import com.github.davidmoten.aws.lw.client.Multipart;
import com.github.davidmoten.aws.lw.client.Response;
import com.github.davidmoten.aws.lw.client.ResponseInputStream;
import com.github.davidmoten.aws.lw.client.ServiceException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URL;
import java.util.*;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = S3FileStoreService.Config.class)
@Slf4j
public class S3FileStoreService implements FileStoreService {

    @ObjectClassDefinition()
    public @interface Config {

        @AttributeDefinition(name = "Protocol", description = "Protocol of URL stream handler")
        String protocol();

        @AttributeDefinition(name = "Bucket name", description = "S3 bucket name")
        String bucketName();

        @AttributeDefinition(name = "Endpoint", description = "S3 endpoint URL (for MinIO/custom S3)")
        String endpoint() default "";

        @AttributeDefinition(name = "Access key", description = "S3 access key")
        String accessKey();

        @AttributeDefinition(name = "Secret key", description = "S3 secret key")
        String secretKey();

        @AttributeDefinition(name = "Region", description = "S3 region")
        String region() default "us-east-1";
    }

    public static final String FILE_ID_CANNOT_BE_NULL = "fileId cannot be null";
    public static final String NOT_FOUND_MESSAGE = "File not found";
    public static final String META_FILENAME = "filename";
    public static final String META_MIME_TYPE = "mimetype";
    public static final String META_CREATE_TIME = "createtime";
    public static final String META_SIZE = "size";
    public static final String MINUS = "-";

    private static final int MULTIPART_THRESHOLD = 5 * 1024 * 1024;

    @Reference
    MimeTypeService mimeTypeService;

    Client s3Client;
    String bucketName;
    String protocol;

    private ServiceRegistration<URLStreamHandlerService> urlStreamHandlerServiceServiceRegistration;

    @Activate
    void activate(BundleContext context, Config config) {
        protocol = config.protocol() != null ? config.protocol().toLowerCase() : null;
        bucketName = config.bucketName();

        if (s3Client == null) {
            var builder = Client.s3()
                    .region(config.region())
                    .accessKey(config.accessKey())
                    .secretKey(config.secretKey());

            if (config.endpoint() != null && !config.endpoint().isEmpty()) {
                String ep = config.endpoint();
                if (!ep.endsWith("/")) {
                    ep = ep + "/";
                }
                String endpoint = ep;
                builder = builder.baseUrlFactory((svc, rgn) -> endpoint);
            }
            s3Client = builder.build();
        }

        if (context != null) {
            Dictionary<String, Object> props = new Hashtable<>();
            props.put("url.handler.protocol", protocol);
            urlStreamHandlerServiceServiceRegistration = context.registerService(
                    URLStreamHandlerService.class, new FileStoreUrlStreamHandler(this), props);
        }
    }

    @Deactivate
    void deactivate() {
        if (urlStreamHandlerServiceServiceRegistration != null) {
            urlStreamHandlerServiceServiceRegistration.unregister();
        }
        urlStreamHandlerServiceServiceRegistration = null;
        s3Client = null;
    }

    @Override
    public String put(InputStream data, String fileName, String mimeType) throws IOException {
        String fileId = UUID.randomUUID().toString().replaceAll(MINUS, "");

        String fn = fileName;
        String mt = mimeType;

        if (isNullOrEmpty(fileName)) {
            if (!isNullOrEmpty(mimeType)) {
                fn = fileId + "." + mimeTypeService.getExtension(mimeType);
            } else {
                fn = fileId + ".bin";
            }
        } else {
            fn = FilenameUtils.makeValidFilename(fileName);
        }
        if (isNullOrEmpty(mimeType)) {
            mt = mimeTypeService.getMimeType(fn);
        }
        if (mt == null) {
            mt = "application/octet-stream";
        }

        String createTime = String.valueOf(System.currentTimeMillis());

        byte[] buffer = new byte[MULTIPART_THRESHOLD];
        int totalRead = 0;
        int bytesRead;
        while (totalRead < MULTIPART_THRESHOLD
                && (bytesRead = data.read(buffer, totalRead, MULTIPART_THRESHOLD - totalRead)) != -1) {
            totalRead += bytesRead;
        }

        if (totalRead < MULTIPART_THRESHOLD) {
            byte[] trimmed = Arrays.copyOf(buffer, totalRead);
            putSmallFile(fileId, trimmed, fn, mt, createTime);
        } else {
            int probe = data.read();
            if (probe == -1) {
                putSmallFile(fileId, buffer, fn, mt, createTime);
            } else {
                InputStream combinedStream = new SequenceInputStream(
                        new ByteArrayInputStream(buffer),
                        new SequenceInputStream(
                                new ByteArrayInputStream(new byte[]{(byte) probe}),
                                data
                        )
                );
                putLargeFile(fileId, combinedStream, fn, mt, createTime);
            }
        }

        return fileId;
    }

    private void putSmallFile(String fileId, byte[] content, String fileName, String mimeType, String createTime) {
        s3Client.path(bucketName, fileId)
                .method(HttpMethod.PUT)
                .header("Content-Type", mimeType)
                .metadata(META_FILENAME, fileName)
                .metadata(META_MIME_TYPE, mimeType)
                .metadata(META_CREATE_TIME, createTime)
                .metadata(META_SIZE, String.valueOf(content.length))
                .requestBody(content)
                .execute();
    }

    private void putLargeFile(String fileId, InputStream combinedStream, String fileName, String mimeType, String createTime) {
        Multipart.s3(s3Client)
                .bucket(bucketName)
                .key(fileId)
                .transformCreateRequest(r -> r
                        .header("Content-Type", mimeType)
                        .metadata(META_FILENAME, fileName)
                        .metadata(META_MIME_TYPE, mimeType)
                        .metadata(META_CREATE_TIME, createTime))
                .upload(() -> combinedStream);
    }

    @Override
    public boolean exists(String id) {
        String fileId = getStrippedId(id);
        return s3Client.path(bucketName, fileId).exists();
    }

    @Override
    public InputStream get(String id) throws IOException {
        String fileId = getStrippedId(id);
        ResponseInputStream response = s3Client.path(bucketName, fileId).responseInputStream();
        if (response.statusCode() == 404) {
            response.close();
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
        return response;
    }

    @Override
    public String getMimeType(String id) throws IOException {
        String fileId = getStrippedId(id);
        try {
            return getObjectMetadata(fileId).get(META_MIME_TYPE);
        } catch (ServiceException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public String getFileName(String id) throws IOException {
        String fileId = getStrippedId(id);
        try {
            return getObjectMetadata(fileId).get(META_FILENAME);
        } catch (ServiceException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public long getSize(String id) throws IOException {
        String fileId = getStrippedId(id);
        try {
            Map<String, String> metadata = getObjectMetadata(fileId);
            String metaSize = metadata.get(META_SIZE);
            if (metaSize != null) {
                return Long.parseLong(metaSize);
            }
            String contentLength = metadata.get("__content-length__");
            if (contentLength != null) {
                return Long.parseLong(contentLength);
            }
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        } catch (ServiceException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public Date getCreateTime(String id) throws IOException {
        String fileId = getStrippedId(id);
        try {
            long createTime = Long.parseLong(getObjectMetadata(fileId).get(META_CREATE_TIME));
            return new Date(createTime);
        } catch (ServiceException e) {
            throw new IllegalArgumentException(NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public URL getAccessUrl(String id) throws IOException {
        String fileId = getStrippedId(id);
        return new URL(protocol + ":" + fileId + MINUS + getFileName(fileId));
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    private Map<String, String> getObjectMetadata(String fileId) {
        Response response = s3Client.path(bucketName, fileId)
                .method(HttpMethod.HEAD)
                .response();
        if (!response.isOk()) {
            throw new ServiceException(response.statusCode(), "Object not found");
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : response.metadata().entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        response.firstHeader("Content-Length")
                .ifPresent(cl -> result.put("__content-length__", cl));
        return result;
    }

    static boolean isNullOrEmpty(String string) {
        return string == null || string.isEmpty();
    }

    private String getStrippedId(String id) {
        if (id == null) {
            throw new IllegalArgumentException(FILE_ID_CANNOT_BE_NULL);
        }
        if (id.contains(":") && id.split(":").length >= 2) {
            return id.split(":")[1];
        }
        return id;
    }
}
