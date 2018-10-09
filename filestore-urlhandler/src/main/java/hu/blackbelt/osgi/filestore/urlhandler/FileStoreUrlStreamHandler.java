package hu.blackbelt.osgi.filestore.urlhandler;

public interface FileStoreUrlStreamHandler {

    /**
     * Get protocol of FileStore URL stream handler.
     *
     * @return protocol (prefix of URLs).
     */
    String getProtocol();
}
