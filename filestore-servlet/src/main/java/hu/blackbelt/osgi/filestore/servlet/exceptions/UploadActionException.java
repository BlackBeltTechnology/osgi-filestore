package hu.blackbelt.osgi.filestore.servlet.exceptions;

/**
 * Exception thrown in user's customized action servlets.
 */
public class UploadActionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UploadActionException(Throwable e) {
        super(e);
    }

    public UploadActionException(String message) {
        super(message);
    }

    public UploadActionException(String message, Throwable e) {
        super(message, e);
    }

}
