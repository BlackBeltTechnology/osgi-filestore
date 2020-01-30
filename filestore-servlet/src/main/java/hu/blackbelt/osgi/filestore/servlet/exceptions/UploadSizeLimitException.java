package hu.blackbelt.osgi.fileupload.exceptions;

import hu.blackbelt.osgi.filestore.servlet.UploadUtils;

/**
 * Exception thrown when the recuest's length exceeds the maximum.
 */
public class UploadSizeLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int actualSize;
    final int maxSize;

    public UploadSizeLimitException(long max, long actual) {
        super();
        actualSize = (int) (actual / 1024);
        maxSize = (int) (max / 1024);
    }

    @Override
    public String getLocalizedMessage() {
        return getMessage();
    }

    @Override
    public String getMessage() {
        return UploadUtils.getMessage("size_limit", actualSize, maxSize);
    }

    public int getActualSize() {
        return actualSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

}
