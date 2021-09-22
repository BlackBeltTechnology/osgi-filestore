package hu.blackbelt.osgi.filestore.servlet.exceptions;

/**
 * Exception thrown when the client cancels the upload transfer.
 */
public class UploadCanceledException extends RuntimeException {
    public UploadCanceledException() { }

    private static final long serialVersionUID = 1L;
}
