package hu.blackbelt.osgi.filestore.servlet;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.json.XML;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import static hu.blackbelt.osgi.filestore.servlet.Constants.EMPTY_STRING;
import static hu.blackbelt.osgi.filestore.servlet.Constants.GT;
import static hu.blackbelt.osgi.filestore.servlet.Constants.LT;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MIMETYPE_APPLICATION_JSON;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MIMETYPE_TEXT_HTML;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MIMETYPE_TEXT_PLAIN;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MSG_S_GET_UPLOADED_FILE_S_FILE_ISN_T_IN_SESSION;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MSG_S_GET_UPLOADED_FILE_S_RETURNING_S_S_D_BYTES;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MSG_S_REMOVE_SESSION_FILE_ITEMS_REMOVE_DATA_S;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MSG_S_REMOVE_UPLOADED_FILE_S_NOT_IN_SESSION;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MSG_S_REMOVE_UPLOADED_FILE_S_S_D;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MULTI_SUFFIX;
import static hu.blackbelt.osgi.filestore.servlet.Constants.NEW_LINE;
import static hu.blackbelt.osgi.filestore.servlet.Constants.PARAM_REMOVE;
import static hu.blackbelt.osgi.filestore.servlet.Constants.PARAM_SHOW;
import static hu.blackbelt.osgi.filestore.servlet.Constants.SESSION_FILES;
import static hu.blackbelt.osgi.filestore.servlet.Constants.SESSION_LAST_FILES;
import static hu.blackbelt.osgi.filestore.servlet.Constants.SLASH;
import static hu.blackbelt.osgi.filestore.servlet.Constants.TAG_MSG_END;
import static hu.blackbelt.osgi.filestore.servlet.Constants.TAG_MSG_GT;
import static hu.blackbelt.osgi.filestore.servlet.Constants.TAG_MSG_LT;
import static hu.blackbelt.osgi.filestore.servlet.Constants.TAG_MSG_START;
import static hu.blackbelt.osgi.filestore.servlet.Constants.XML_DELETED_TRUE;
import static hu.blackbelt.osgi.filestore.servlet.Constants.XML_ERROR_ITEM_NOT_FOUND;
import static hu.blackbelt.osgi.filestore.servlet.Constants.XML_TPL;

@Slf4j
public final class UploadUtils {

    public static final ThreadLocal<HttpServletRequest> PER_THREAD_REQUEST = new ThreadLocal<>();

    private UploadUtils() { }

    /**
     * Copy the content of an input stream to an output one.
     *
     * @param in
     * @param out
     * @throws IOException
     */
    public static void copyFromInputStreamToOutputStream(InputStream in, OutputStream out) throws IOException {
        IOUtils.copy(in, out);
    }

    /**
     * Utility method to get a fileItem of type file from a vector using either
     * the file name or the attribute name.
     *
     * @param sessionFiles
     * @param parameter
     * @return fileItem of the file found or null
     */
    public static org.apache.commons.fileupload.FileItem findFileItem(List<org.apache.commons.fileupload.FileItem> sessionFiles, String parameter) {
        if (sessionFiles == null || parameter == null) {
            return null;
        }

        org.apache.commons.fileupload.FileItem item = findItemByFieldName(sessionFiles, parameter);
        if (item == null) {
            item = findItemByFileName(sessionFiles, parameter);
        }
        if (item != null && !item.isFormField()) {
            return item;
        }

        return null;
    }

    /**
     * Utility method to get a fileItem from a vector using the attribute name.
     *
     * @param sessionFiles
     * @param attrName
     * @return fileItem found or null
     */
    public static org.apache.commons.fileupload.FileItem findItemByFieldName(List<org.apache.commons.fileupload.FileItem> sessionFiles,
                                                                             String attrName) {
        if (sessionFiles != null) {
            for (org.apache.commons.fileupload.FileItem fileItem : sessionFiles) {
                if (fileItem.getFieldName().equalsIgnoreCase(attrName)) {
                    return fileItem;
                }
            }
        }
        return null;
    }

    /**
     * Utility method to get a fileItem from a vector using the file name It
     * only returns items of type file.
     *
     * @param sessionFiles
     * @param fileName
     * @return fileItem of the file found or null
     */
    public static org.apache.commons.fileupload.FileItem findItemByFileName(List<org.apache.commons.fileupload.FileItem> sessionFiles,
                                                                            String fileName) {
        if (sessionFiles != null) {
            for (org.apache.commons.fileupload.FileItem fileItem : sessionFiles) {
                if (!fileItem.isFormField() && fileItem.getName().equalsIgnoreCase(fileName)) {
                    return fileItem;
                }
            }
        }
        return null;
    }

    /**
     * Return the list of FileItems stored in session under the provided session key.
     */
    public static List<org.apache.commons.fileupload.FileItem> getSessionFileItems(HttpServletRequest request, String sessionFilesKey) {
        return (List<org.apache.commons.fileupload.FileItem>) request.getSession().getAttribute(sessionFilesKey);
    }

    /**
     * Return the list of FileItems stored in session under the default name.
     */
    public static List<org.apache.commons.fileupload.FileItem> getSessionFileItems(HttpServletRequest request) {
        return getSessionFileItems(request, SESSION_FILES);
    }

    /**
     * Return the most recent list of FileItems received.
     */
    public static List<org.apache.commons.fileupload.FileItem> getLastReceivedFileItems(HttpServletRequest request, String sessionLastFilesKey) {
        return (List<org.apache.commons.fileupload.FileItem>) request.getSession().getAttribute(sessionLastFilesKey);
    }

    /**
     * Return the most recent list of FileItems received under the default key.
     */
    public static List<org.apache.commons.fileupload.FileItem> getLastReceivedFileItems(HttpServletRequest request) {
        return getLastReceivedFileItems(request, SESSION_LAST_FILES);
    }

    /**
     * Returns the localized text of a key.
     */
    public static String getMessage(String key, Object... pars) {
        Locale loc =
                getThreadLocalRequest() == null || getThreadLocalRequest().getLocale() == null
                        ? new Locale("en")
                        : getThreadLocalRequest().getLocale();

        ResourceBundle res =
                ResourceBundle.getBundle("UploadServlet", loc, UploadUtils.class.getClassLoader());

        String msg = res.getString(key);
        return new MessageFormat(msg, loc).format(pars);
    }

    public static HttpServletRequest getThreadLocalRequest() {
        return PER_THREAD_REQUEST.get();
    }

    /**
     * Removes all FileItems stored in session under the session key and the temporary data.
     *
     * @param request
     */
    public static void removeSessionFileItems(HttpServletRequest request, String sessionFilesKey) {
        removeSessionFileItems(request, sessionFilesKey, true);
    }

    /**
     * Removes all FileItems stored in session under the default key and the temporary data.
     */
    public static void removeSessionFileItems(HttpServletRequest request) {
        removeSessionFileItems(request, SESSION_FILES, true);
        removeSessionFileItems(request, SESSION_LAST_FILES, true);
    }

    /**
     * Removes all FileItems stored in session under the session key, but in this case
     * the user can specify whether the temporary data is removed from disk.
     *
     * @param request
     * @param removeData
     *                    true: the file data is deleted.
     *                    false: use it when you are referencing file items
     *                    instead of copying them.
     */
    public static void removeSessionFileItems(HttpServletRequest request, String sessionFilesKey, boolean removeData) {
        log.debug(String.format(MSG_S_REMOVE_SESSION_FILE_ITEMS_REMOVE_DATA_S, request.getSession().getId(), removeData));
        List<org.apache.commons.fileupload.FileItem> sessionFiles = getSessionFileItems(request, sessionFilesKey);
        if (removeData && sessionFiles != null) {
            for (org.apache.commons.fileupload.FileItem fileItem : sessionFiles) {
                if (fileItem != null && !fileItem.isFormField()) {
                    fileItem.delete();
                }
            }
        }
        request.getSession().removeAttribute(sessionFilesKey);
    }

    /**
     * Removes all FileItems stored in session under the default key, but in this case
     * the user can specify whether the temporary data is removed from disk.
     */
    public static void removeSessionFileItems(HttpServletRequest request, boolean removeData) {
        removeSessionFileItems(request, SESSION_FILES, removeData);
        removeSessionFileItems(request, SESSION_LAST_FILES, removeData);
    }

    /**
     * Delete an uploaded file.
     *
     * @param request
     * @param response
     * @return FileItem
     * @throws IOException
     */
    protected static org.apache.commons.fileupload.FileItem removeUploadedFile(HttpServletRequest request,
                                                                               HttpServletResponse response) throws IOException {
        String parameter = request.getParameter(PARAM_REMOVE);

        org.apache.commons.fileupload.FileItem item = findFileItem(getSessionFileItems(request), parameter);
        if (item != null) {
            getSessionFileItems(request).remove(item);
            log.debug(String.format(MSG_S_REMOVE_UPLOADED_FILE_S_S_D, request.getSession().getId(), parameter, item.getName(), item.getSize()));
        } else {
            log.info(String.format(MSG_S_REMOVE_UPLOADED_FILE_S_NOT_IN_SESSION, request.getSession().getId(), parameter));
        }

        renderXmlResponse(request, response, XML_DELETED_TRUE);
        return item;
    }

    /**
     * Writes a response to the client.
     */
    protected static void renderMessage(HttpServletResponse response, String message, String contentType) throws IOException {
        response.addHeader("Cache-Control", "no-cache");
        response.setContentType(contentType + "; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        out.print(message);
        out.flush();
        out.close();
    }

    /**
     * Writes a XML response to the client.
     * The message must be a text which will be wrapped in an XML structure.
     *
     * Note: if the request is a POST, the response should set the content type
     *  to text/html or text/plain in order to be able in the client side to
     *  read the iframe body (submitCompletEvent.getResults()), otherwise the
     *  method returns null
     *
     * @param request
     * @param response
     * @param message
     * @param post
     *        specify whether the request is post or not.
     * @throws IOException
     */
    protected static void renderXmlResponse(HttpServletRequest request, HttpServletResponse response,
                                            String message, boolean post) throws IOException {
        String contentType = post ? MIMETYPE_TEXT_PLAIN : MIMETYPE_TEXT_HTML;

        String xml = XML_TPL.replace("%%MESSAGE%%", message != null ? message : EMPTY_STRING);
        if (post) {
            xml = TAG_MSG_START + xml.replaceAll(LT, TAG_MSG_LT).replaceAll(GT, TAG_MSG_GT) + TAG_MSG_END;
        }

        renderMessage(response, xml, contentType);
    }

    protected static void renderXmlResponse(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        renderXmlResponse(request, response, message, false);
    }

    protected static void renderJsonResponse(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        renderMessage(response, XML.toJSONObject(message).toString(), MIMETYPE_APPLICATION_JSON);
    }



    protected static void setThreadLocalRequest(HttpServletRequest request) {
        PER_THREAD_REQUEST.set(request);
    }

    public static String statusToString(Map<String, String> stat) {
        String message = EMPTY_STRING;
        for (Map.Entry<String, String> e : stat.entrySet()) {
            if (e.getValue() != null) {
                String k = e.getKey();
                String v = e.getValue().replaceAll("</*pre>", EMPTY_STRING).replaceAll("&lt;", LT).replaceAll("&gt;", GT);
                message += LT + k + GT + v + LT + SLASH + k + GT + NEW_LINE;
            }
        }
        return message;
    }

    public static long getContentLength(HttpServletRequest request) {
        long size = -1;
        try {
            size = Long.parseLong(request.getHeader(org.apache.commons.fileupload.FileUploadBase.CONTENT_LENGTH));
        } catch (NumberFormatException e) {
            // TODO: Notning
        }
        return size;
    }

    /**
     * Get an uploaded file item.
     *
     * @param request
     * @param response
     * @throws IOException
     */
    public static void getUploadedFile(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String parameter = request.getParameter(PARAM_SHOW);
        org.apache.commons.fileupload.FileItem item = UploadUtils.findFileItem(getMySessionFileItems(request), parameter);
        if (item != null) {
            log.error(String.format(MSG_S_GET_UPLOADED_FILE_S_RETURNING_S_S_D_BYTES, request.getSession().getId(), parameter,
                    item.getContentType(), item.getName(), item.getSize()));
            response.setContentType(item.getContentType());
            copyFromInputStreamToOutputStream(item.getInputStream(), response.getOutputStream());
        } else {
            log.error(String.format(MSG_S_GET_UPLOADED_FILE_S_FILE_ISN_T_IN_SESSION, request.getSession().getId(), parameter));
            renderXmlResponse(request, response, XML_ERROR_ITEM_NOT_FOUND);
        }
    }


    /**
     * Return the list of FileItems stored in session under the session key.
     */
    // FIXME(manolo): Not sure about the convenience of this and sessionFilesKey.
    public static List<org.apache.commons.fileupload.FileItem> getMySessionFileItems(HttpServletRequest request) {
        return UploadUtils.getSessionFileItems(request, getSessionFilesKey(request));
    }

    /**
     * Return the most recent list of FileItems received under the session key.
     */
    public static List<org.apache.commons.fileupload.FileItem> getMyLastReceivedFileItems(HttpServletRequest request) {
        return UploadUtils.getLastReceivedFileItems(request, getSessionLastFilesKey(request));
    }

    /**
     * Override this to provide a session key which allow to differentiate between
     * multiple instances of uploaders in an application with the same session but
     * who do not wish to share the uploaded files.
     * Example:
     * protected String getSessionFilesKey(HttpServletRequest request) {
     *  return getSessionFilesKey(request.getParameter("randomNumber"));
     * }
     *
     * public static String getSessionFilesKey(String parameter) {
     *  return "SESSION_FILES_" + parameter;
     * }
     *
     */
    public static String getSessionFilesKey(HttpServletRequest request) {
        return SESSION_FILES;
    }

    /**
     * Override this to provide a session key which allow to differentiate between
     * multiple instances of uploaders in an application with the same session but
     * who do not wish to share the uploaded files.
     * See getSessionFilesKey() for an example.
     */
    public static String getSessionLastFilesKey(HttpServletRequest request) {
        return SESSION_LAST_FILES;
    }

    /**
     * DiskFileItemFactory for Multiple file selection.
     */
    public static class DefaultFileItemFactory extends org.apache.commons.fileupload.disk.DiskFileItemFactory {
        private Map<String, Integer> map = new HashMap<String, Integer>();

        public DefaultFileItemFactory() { }

        @Override
        public org.apache.commons.fileupload.FileItem createItem(String fieldName, String contentType, boolean isFormField, String fileName) {
            Integer cont = map.get(fieldName) != null ? (map.get(fieldName) + 1) : 0;
            map.put(fieldName, cont);
            String fn = fieldName.replace(MULTI_SUFFIX, "") + "-" + cont;
            return super.createItem(fn, contentType, isFormField, fileName);
        }
    }


}
