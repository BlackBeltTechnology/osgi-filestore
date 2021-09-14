package hu.blackbelt.osgi.filestore.servlet.exceptions;

/**
 * Exception thrown UploadServlet when an unexpected error happens
 * in the data reception.
 */
public class UploadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UploadException(Throwable e) {
        super(e);
    }
}
