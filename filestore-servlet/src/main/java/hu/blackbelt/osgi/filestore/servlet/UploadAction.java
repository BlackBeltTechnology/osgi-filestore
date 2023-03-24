package hu.blackbelt.osgi.filestore.servlet;

/*-
 * #%L
 * Filestore servlet (file upload)
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import hu.blackbelt.osgi.filestore.servlet.exceptions.UploadActionException;
import hu.blackbelt.osgi.filestore.servlet.exceptions.UploadCanceledException;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static hu.blackbelt.osgi.filestore.servlet.Constants.*;
import static hu.blackbelt.osgi.filestore.servlet.Constants.PARAM_KEEP_SESSION;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.*;

/**
 * <p>Class used to manipulate the data received in the server side.</p>
 *
 * The user has to implement the method executeAction which receives the list of the FileItems
 * sent to the server. Each FileItem represents a file or a form field.
 *
 * <p>Note: Temporary files are not deleted until the user calls removeSessionFiles(request).</p>
 *
 */
@Slf4j
public class UploadAction extends UploadServlet {
    private static final long serialVersionUID = -6790246163691420791L;

    private boolean removeSessionFiles;
    private boolean removeData;

    public UploadAction() { }

    /**
     * Returns the content of a file as an InputStream if it is found in the
     * FileItem vector.
     *
     * @param sessionFiles collection of files sent by the client
     * @param parameter field name or file name of the desired file
     * @return an ImputString
     */
    public static InputStream getFileStream(List<org.apache.commons.fileupload.FileItem> sessionFiles, String parameter) throws IOException {
        org.apache.commons.fileupload.FileItem item = findFileItem(sessionFiles, parameter);
        return item == null ? null : item.getInputStream();
    }

    /**
     * Returns the value of a text field present in the FileItem collection.
     *
     * @param sessionFiles collection of fields sent by the client
     * @param fieldName field name
     * @return the string value
     */
    public static String getFormField(List<org.apache.commons.fileupload.FileItem> sessionFiles, String fieldName) {
        org.apache.commons.fileupload.FileItem item = findItemByFieldName(sessionFiles, fieldName);
        return item == null || !item.isFormField() ? null : item.getString();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        ServletContext ctx = config.getServletContext();
        removeSessionFiles = Boolean.valueOf(ctx.getInitParameter("removeSessionFiles"));
        removeData = Boolean.valueOf(ctx.getInitParameter("removeData"));

        log.info("UPLOAD-ACTION init: removeSessionFiles=" + removeSessionFiles + ", removeData=" + removeData);
    }

    /**
     * This method is called when all data is received in the server.
     *
     * Temporary files are not deleted until the user calls removeSessionFileItems(request)
     *
     * Override this method to customize the behavior
     *
     * @param request
     * @param sessionFiles
     *
     * @return the text/html message to be sent to the client.
     *         In the case of null the standard response configured for this
     *         action will be sent.
     *
     * @throws UploadActionException
     *         In the case of error
     *
     */
    public String executeAction(HttpServletRequest request, List<org.apache.commons.fileupload.FileItem> sessionFiles) throws UploadActionException {
        return null;
    }

    /**
     * This method is called when a received file is requested to be removed and
     * is in the collection of items stored in session.
     * If the item does't exist in session this method is not called
     *
     * After it, the item is removed from the session items collection.
     *
     * Override this method to customize the behavior
     *
     * @param request
     * @param item    The item in session
     *
     * @throws UploadActionException
     *         In the case of an error, the exception message is returned to
     *         the client and the item is not deleted from session
     *
     */
    public void removeItem(HttpServletRequest request, org.apache.commons.fileupload.FileItem item)  throws UploadActionException {
    }

    /**
     * This method is called when a received file is requested to be removed.
     * After it, the item is removed from the session items collection.
     *
     * Override this method to customize the behavior
     *
     * @param request
     * @param fieldName    The name of the filename input
     *
     * @throws UploadActionException
     *         In the case of an error, the exception message is returned to
     *         the client and the item is not deleted from session
     *
     */
    public void removeItem(HttpServletRequest request, String fieldName)  throws UploadActionException {
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException  {
        String parameter = request.getParameter(PARAM_REMOVE);
        if (parameter != null) {
            try {
                // Notify classes extending this that they have to remove the item.
                removeItem(request, parameter);
                // Other way to notify classes extending this.
                org.apache.commons.fileupload.FileItem item = findFileItem(getMySessionFileItems(request), parameter);
                if (item != null) {
                    removeItem(request, item);
                }
            } catch (Exception e) {
                renderXmlResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
                return;
            }
            // Remove the item saved in session in the case it was not removed yet
            removeUploadedFile(request, response);
        } else {
            super.doGet(request, response);
        }
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String error = null;
        String message = null;
        Map<String, String> tags = new HashMap<String, String>();

        PER_THREAD_REQUEST.set(request);
        try {
            // Receive the files and form elements, updating the progress status
            error = super.parsePostRequest(request, response, null);
            if (error == null) {
                // Fill files status before executing user code which could remove session files
                getFileItemsSummary(request, tags);
                // Call to the user code
                message = executeAction(request, getMyLastReceivedFileItems(request));
            }
        } catch (UploadCanceledException e) {
            renderXmlResponse(request, response, String.format(XML_CANCELED_S_CANCELED, TRUE));
            return;
        } catch (UploadActionException e) {
            log.info("ExecuteUploadActionException when receiving a file.", e);
            error =  e.getMessage();
        } catch (Exception e) {
            log.info("Unknown Exception when receiving a file.", e);
            error = e.getMessage();
        } finally {
            PER_THREAD_REQUEST.set(null);
        }

        String postResponse = null;
        AbstractUploadListener listener = getCurrentListener(request);
        if (error != null) {
            postResponse = String.format(XML_ERROR_S_ERROR, error);
            renderXmlResponse(request, response, postResponse);
            if (listener != null) {
                listener.setException(new RuntimeException(error));
            }
            removeSessionFileItems(request);
        } else {
            if (message != null) {
                // see issue #139
                tags.put("message", "<![CDATA[" + message + "]]>");
            }
            postResponse = statusToString(tags);
            renderXmlResponse(request, response, postResponse, true);
        }
        finish(request, postResponse, request.getParameter(PARAM_KEEP_SESSION) != null ? Boolean.parseBoolean(request.getParameter(PARAM_KEEP_SESSION)) : false);

        if (removeSessionFiles) {
            removeSessionFileItems(request, removeData);
        }
    }
}
