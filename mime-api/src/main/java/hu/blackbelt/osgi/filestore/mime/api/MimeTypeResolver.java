package hu.blackbelt.osgi.filestore.mime.api;

public interface MimeTypeResolver {
    /**
     * Returns the MIME type of the extension of the given <code>name</code>.
     * The extension is the part of the name after the last dot. If the name
     * does not contain a dot, the name as a whole is assumed to be the
     * extension.
     *
     * @param url The URL for which the MIME type is to be returned.
     * @return The MIME type for the extension of the name. If the extension
     * cannot be mapped to a MIME type or <code>name</code> is
     * <code>null</code>, <code>null</code> is returned.
     * @see #getExtension(String)
     */
    String getMimeType(String url);

    /**
     * Returns the primary name extension to which the given
     * <code>mimeType</code> maps. The returned extension must map to the given
     * <code>mimeType</code> when fed to the {@link #getMimeType(String)}
     * method. In other words, the expression
     * <code>mimeType.equals(getMimeType(getExtension(mimeType)))</code> must
     * always be <code>true</code> for any non-<code>null</code> MIME type.
     * <p>
     * A MIME type may be mapped to multiple extensions (e.g.
     * <code>text/plain</code> to <code>txt</code>, <code>log</code>, ...). This
     * method is expected to returned one of those extensions. It is up to the
     * implementation to select an appropriate extension if multiple mappings
     * exist for a single MIME type.
     *
     * @param mimeType The MIME type whose primary extension is requested.
     * @return A extension which maps to the given MIME type or
     * <code>null</code> if no such mapping exists.
     * @see #getMimeType(String)
     */
    String getExtension(String mimeType);
}
