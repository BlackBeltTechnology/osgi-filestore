package hu.blackbelt.osgi.filestore.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;

/**
 * Represents a file store service.
 */
public interface FileStoreService {

    /**
     * Saves a file with the given <code>fileName</code> and <code>mimeType</code> to the data store. It returns the ID of the file which is used
     * to access.
     *
     * @param data
     * @param fileName
     * @param mimeType
     */
    String put(InputStream data, String fileName, String mimeType) throws IOException;

    /**
     * Checks the existence of a file <code>fileId</code> (If any representation exists return true).
     *
     * @param fileId
     * @throws IOException
     */
    boolean exists(String fileId);

    /**
     * Fetches the file with the given <code>fileId</code>.
     *
     * @param fileId
     * @return
     */
    InputStream get(String fileId) throws IOException;

    /**
     * Gets the mime type of the given fileId.
     *
     * @param fileId
     * @return
     * @throws IOException
     */
    String getMimeType(String fileId) throws IOException;

    /**
     * Get the file name of the given fileId.
     *
     * @param fileId
     * @return
     * @throws IOException
     */
    String getFileName(String fileId) throws IOException;


    /**
     * Get the file size of the given fileId.
     *
     * @param fileId
     * @return
     * @throws IOException
     */
    long getSize(String fileId) throws IOException;

    /**
     * Get the create time of the given fileId. Type is java.util.Date supporting for applications not supporting new date/time APIs.
     *
     * @param fileId
     * @return
     * @throws IOException
     */
    Date getCreateTime(String fileId) throws IOException;

    /**
     * Get absolute URL of file.
     *
     * @return
     * @throws IOException
     */
    URL getAccessUrl(String fileId) throws IOException;

    /**
     * Get protocol of file store service.
     *
     * @return
     */
    String getProtocol();
}
