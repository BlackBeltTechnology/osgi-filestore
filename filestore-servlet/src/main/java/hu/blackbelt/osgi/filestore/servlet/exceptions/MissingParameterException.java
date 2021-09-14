package hu.blackbelt.osgi.filestore.servlet.exceptions;

public class MissingParameterException extends Exception {

    private static final long serialVersionUID = 1L;

    public MissingParameterException(final String message) {
        super(message);
    }
}
