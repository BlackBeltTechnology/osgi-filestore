package hu.blackbelt.osgi.filestore.servlet.exceptions;

/**
 * Exception thrown when the upload process hangs.
 */
public class UploadTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public UploadTimeoutException(String msg) {
        super(msg);
    }
}
