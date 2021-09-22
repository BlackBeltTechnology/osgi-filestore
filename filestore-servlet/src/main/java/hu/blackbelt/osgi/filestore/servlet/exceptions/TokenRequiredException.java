package hu.blackbelt.osgi.filestore.servlet.exceptions;

public class TokenRequiredException extends Exception {

    private static final long serialVersionUID = 1L;

    public TokenRequiredException(final String message) {
        super(message);
    }
}
