package hu.blackbelt.osgi.filestore.security.api.exceptions;

public class InvalidTokenException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidTokenException(final Throwable exception) {
        super(exception);
    }
}
