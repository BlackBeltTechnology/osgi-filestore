package hu.blackbelt.osgi.filestore.servlet;

import hu.blackbelt.osgi.fileupload.exceptions.UploadActionException;
import hu.blackbelt.osgi.fileupload.exceptions.UploadCanceledException;
import hu.blackbelt.osgi.fileupload.exceptions.UploadException;
import hu.blackbelt.osgi.fileupload.exceptions.UploadSizeLimitException;
import hu.blackbelt.osgi.fileupload.exceptions.UploadTimeoutException;
import hu.blackbelt.osgi.filestore.api.FileStoreService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.osgi.service.component.annotations.*;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static hu.blackbelt.osgi.filestore.servlet.Constants.*;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.getContentLength;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.getMyLastReceivedFileItems;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.getMySessionFileItems;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.getSessionFilesKey;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.getSessionLastFilesKey;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.getUploadedFile;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.renderJsonResponse;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.renderMessage;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.statusToString;

/**
 * Upload servlet.
 * Set the request size to 512 KB which is the maximal size allowed
 * Store received data in memory and cache instead of file system
 */
@Slf4j
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = UploadServlet.Config.class)
public class UploadServlet extends HttpServlet implements Servlet {

    @ObjectClassDefinition()
    public @interface Config {

        @AttributeDefinition(required = false, name = "Max request size (kB)", type = AttributeType.LONG)
        long maxSize() default Constants.DEFAULT_REQUEST_LIMIT_KB;

        @AttributeDefinition(required = false, name = "Max file size (kB)", type = AttributeType.LONG)
        long maxFileSize() default Constants.DEFAULT_REQUEST_LIMIT_KB;

        @AttributeDefinition(required = false, name = "Slow uploads delay (ms)", type = AttributeType.INTEGER)
        int slowUploads() default Constants.DEFAULT_SLOW_DELAY_MILLIS;

        @AttributeDefinition(required = false, name = "No data timeout", type = AttributeType.INTEGER)
        int noDataTimeout() default 20000;

        @AttributeDefinition(required = false, name = "CORS domain regular expression")
        String corsDomainRegex() default "^$";

        @AttributeDefinition(name = "Servlet path")
        String servletPath();
    }

    public static final ThreadLocal<HttpServletRequest> PER_THREAD_REQUEST = new ThreadLocal<>();


    protected long maxSize;
    protected long maxFileSize;
    protected int noDataTimeout;
    protected int uploadDelay;
    private String corsDomainsRegex;
    private String servletPath;

    @Reference
    private HttpService httpService;

    @Reference
    private FileStoreService fileStoreService;

    public UploadServlet() { }

    @Activate
    @SneakyThrows({ ServletException.class, NamespaceException.class })
    protected void activate(Config config) {
        maxSize = config.maxSize();
        maxFileSize = config.maxFileSize();
        noDataTimeout = config.noDataTimeout();
        uploadDelay = config.slowUploads();
        corsDomainsRegex = config.corsDomainRegex();
        servletPath = config.servletPath();

        log.info(String.format(MSG_INIT_MAX_SIZE_D_UPLOAD_DELAY_D_CORS_REGEX_S, maxSize, uploadDelay, corsDomainsRegex));
        httpService.registerServlet(servletPath, this, null, null);
    }

    @Deactivate
    protected void deactivate() {
        httpService.unregister(servletPath);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (checkCORS(request, response) && METHOD_OPTIONS.equals(request.getMethod())) {
            String method = request.getHeader(HEADER_ACCESS_CONTROL_REQUEST_METHOD);
            if (method != null) {
                response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_METHODS, method);
                response.setHeader(HEADER_ALLOW, ALLOW_VALUE);
            }
            String headers = request.getHeader(HEADER_ACCESS_CONTROL_REQUEST_HEADERS);
            if (headers != null) {
                response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_HEADERS, headers);
            }
            response.setContentType(MIMETYPE_TEXT_PLAIN);
        }
        super.service(request, response);
    }

    /**
     * Mark the current upload process to be canceled.
     *
     * @param request
     */
    public void cancelUpload(HttpServletRequest request) {
        log.debug(String.format(MSG_S_CANCELLING_UPLOAD, request.getSession().getId()));
        AbstractUploadListener listener = getCurrentListener(request);
        if (listener != null && !listener.isCanceled()) {
            listener.setException(new UploadCanceledException());
        }
    }

    /**
     * Override this method if you want to check the request before it is passed
     * to commons-fileupload parser.
     *
     * @param request
     * @throws RuntimeException
     */
    public void checkRequest(HttpServletRequest request) {
        log.debug(String.format(MSG_S_PROCESING_A_REQUEST_WITH_SIZE_D_BYTES, request.getSession().getId(), getContentLength(request)));
        if (getContentLength(request) > maxSize) {
            throw new UploadSizeLimitException(maxSize, getContentLength(request));
        }
    }


    @Override
    public String getInitParameter(String name) {
        String value = getServletContext().getInitParameter(name);
        if (value == null) {
            value = super.getInitParameter(name);
        }
        return value;
    }


    /**
     * Create a new listener for this session.
     *
     * @param request
     * @return the appropriate listener
     */
    protected AbstractUploadListener createNewListener(HttpServletRequest request) {
        int delay = request.getParameter("nodelay") != null ? 0 : uploadDelay;
        return new UploadListener(delay, getContentLength(request), noDataTimeout);
    }

    /**
     * The get method is used to monitor the uploading process or to get the
     * content of the uploaded files.
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        PER_THREAD_REQUEST.set(request);
        try {
            AbstractUploadListener listener = getCurrentListener(request);
            if (request.getParameter(Constants.PARAM_SESSION) != null) {
                log.debug(String.format(MSG_S_NEW_SESSION, request.getSession().getId()));
                String sessionId = request.getSession().getId();
                renderJsonResponse(request, response, String.format(XML_SESSIONID_S_SESSIONID, sessionId));
            } else if (request.getParameter(PARAM_SHOW) != null) {
                getUploadedFile(request, response);
            } else if (request.getParameter(PARAM_CANCEL) != null) {
                cancelUpload(request);
                renderJsonResponse(request, response, String.format(XML_CANCELED_S_CANCELED, TRUE));
            } else if (request.getParameter(PARAM_REMOVE) != null) {
                UploadUtils.removeUploadedFile(request, response);
            } else if (request.getParameter(Constants.PARAM_CLEAN) != null) {
                log.debug(String.format(MSG_S_CLEAN_LISTENER, request.getSession().getId()));
                if (listener != null) {
                    listener.remove();
                }
                renderJsonResponse(request, response, String.format(XML_FINISHED_S_FINISHED, "OK"));
            } else if (listener != null && listener.isFinished()) {
                removeCurrentListener(request);
                renderJsonResponse(request, response, listener.getPostResponse());
            } else {
                String message = statusToString(getUploadStatus(request, request.getParameter(Constants.PARAM_FILENAME), null));
                renderJsonResponse(request, response, message);
            }
        } finally {
            PER_THREAD_REQUEST.set(null);
        }
    }


    /**
     * The post method is used to receive the file and save it in the user
     * session. It returns a very XML page that the client receives in an
     * iframe.
     *
     * The content of this xml document has a tag error in the case of error in
     * the upload process or the string OK in the case of success.
     *
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        PER_THREAD_REQUEST.set(request);
        String error;
        try {
            error = parsePostRequest(request, response);
            String postResponse = "";
            Map<String, String> stat = new HashMap<>();
            if (error != null && error.length() > 0) {
                postResponse = String.format("{\"error\":\"%s\"}", error);
            } else {
                List<String> allFiles = new ArrayList<>();
                for (org.apache.commons.fileupload.FileItem f : getMyLastReceivedFileItems(request)) {
                    String id = fileStoreService.put(f.getInputStream(), f.getName(), f.getContentType());
                    URL url = fileStoreService.getAccessUrl(id);
                    allFiles.add(String.format("{\"field\":\"%s\",\"id\":\"%s\",\"name\":\"%s\",\"url\":\"%s\",\"ctype\":\"%s\",\"size\":%d}",
                            f.getFieldName(), id, f.getName(), url.toString(), f.getContentType(), f.getSize()));
                }
                postResponse = "{\"files\":[" + String.join(",", allFiles) + "],\"finished\":\"ok\"}";
            }
            finish(request, postResponse);
            renderMessage(response, postResponse, MIMETYPE_APPLICATION_JSON);
        } catch (UploadCanceledException e) {
            renderJsonResponse(request, response, String.format(XML_CANCELED_S_CANCELED, TRUE));
        } catch (UploadTimeoutException e) {
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, MSG_TIMEOUT_RECEIVING_FILE));
        } catch (UploadSizeLimitException e) {
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } catch (Exception e) {
            log.error(String.format(MSG_S_EXCEPTION_S, request.getSession().getId(), e.getMessage()), e);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } finally {
            PER_THREAD_REQUEST.set(null);
        }
    }

    protected boolean checkCORS(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin != null && origin.matches(corsDomainsRegex)) {
            // Maybe the user has used this domain before and has a session-cookie, we delete it
            //   Cookie c  = new Cookie("JSESSIONID", "");
            //   c.setMaxAge(0);
            //   response.addCookie(c);
            // All doXX methods should set these header
            response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS, TRUE);
            return true;
        } else if (origin != null) {
            log.warn(String.format(MSG_CHECK_CORS_ERROR_ORIGIN_S_DOES_NOT_MATCH_S, origin, corsDomainsRegex));
        }
        return false;
    }

    protected Map<String, String> getFileItemsSummary(HttpServletRequest request, Map<String, String> pStat) {
        Map<String, String> stat = pStat;
        if (stat == null) {
            stat = new HashMap<>();
        }
        List<org.apache.commons.fileupload.FileItem> s = getMyLastReceivedFileItems(request);
        if (s != null) {
            String files = EMPTY_STRING;
            String params = EMPTY_STRING;
            for (org.apache.commons.fileupload.FileItem i : s) {
                if (i.isFormField()) {
                    params += formFieldToXml(i);
                } else {
                    files += fileFieldToXml(i);
                }
            }
            stat.put(TAG_FILES, files);
            stat.put(TAG_PARAMS, params);
            stat.put(TAG_FINISHED, Constants.RESP_OK);
        }
        return stat;
    }

    private String formFieldToXml(org.apache.commons.fileupload.FileItem i) {
        Map<String, String> item = new HashMap<String, String>();
        item.put(TAG_VALUE, EMPTY_STRING + i.getString());
        item.put(TAG_FIELD, EMPTY_STRING + i.getFieldName());

        Map<String, String> param = new HashMap<String, String>();
        param.put(TAG_PARAM, statusToString(item));
        return statusToString(param);
    }

    private String fileFieldToXml(org.apache.commons.fileupload.FileItem i) {
        Map<String, String> item = new HashMap<String, String>();
        item.put(TAG_CTYPE, i.getContentType() != null ? i.getContentType() : UNKNOWN);
        item.put(TAG_SIZE, EMPTY_STRING + i.getSize());
        item.put(TAG_NAME, EMPTY_STRING + i.getName());
        item.put(TAG_FIELD, EMPTY_STRING + i.getFieldName());
        if (i instanceof HasKey) {
            String k = ((HasKey) i).getKeyString();
            item.put(TAG_KEY, k);
        }

        Map<String, String> file = new HashMap<String, String>();
        file.put(TAG_FILE, statusToString(item));
        return statusToString(file);
    }

    /**
     * Notify to the listener that the upload has finished.
     *
     * @param request
     * @param postResponse
     */
    protected void finish(HttpServletRequest request, String postResponse) {
        AbstractUploadListener listener = getCurrentListener(request);
        if (listener != null) {
            listener.setFinished(postResponse);
        }
    }

    /**
     * Get the listener active in this session.
     *
     * @param request
     * @return the listener active
     */
    protected AbstractUploadListener getCurrentListener(HttpServletRequest request) {
        return UploadListener.current(request);
    }

    /**
     * Override this method if you want to implement a different ItemFactory.
     *
     * @return FileItemFactory
     */
    protected org.apache.commons.fileupload.FileItemFactory getFileItemFactory(long requestSize) {
        return new UploadUtils.DefaultFileItemFactory();
    }

    /**
     * Method executed each time the client asks the server for the progress status.
     * It uses the listener to generate the adequate response
     *
     * @param request
     * @param fieldname
     * @return a map of tag/values to be rendered
     */
    protected Map<String, String> getUploadStatus(HttpServletRequest request, String fieldname, Map<String, String> pRet) {
        PER_THREAD_REQUEST.set(request);
        HttpSession session = request.getSession();

        Map<String, String> ret = pRet;
        if (ret == null) {
            ret = new HashMap<String, String>();
        }

        long currentBytes = 0;
        long totalBytes = 0;
        long percent = 0;
        AbstractUploadListener listener = getCurrentListener(request);
        if (listener != null) {
            if (listener.isFinished()) {
                // TODO: Nothing
            } else if (listener.getException() != null) {
                if (listener.getException() instanceof UploadCanceledException) {
                    ret.put(TAG_CANCELED, TRUE);
                    ret.put(TAG_FINISHED, TAG_CANCELED);
                    log.error(String.format(MSG_S_GET_UPLOAD_STATUS_S_CANCELED_BY_THE_USER_AFTER_D_BYTES,
                            request.getSession().getId(), fieldname, listener.getBytesRead()));
                } else {
                    String errorMsg = UploadUtils.getMessage(KEY_SERVER_ERROR, listener.getException().getMessage());
                    ret.put(TAG_ERROR, errorMsg);
                    ret.put(TAG_FINISHED, TAG_ERROR);
                    log.error(String.format(MSG_S_GET_UPLOAD_STATUS_S_FINISHED_WITH_ERROR_S, request.getSession().getId(),
                            fieldname, listener.getException().getMessage()));
                }
            } else {
                currentBytes = listener.getBytesRead();
                totalBytes = listener.getContentLength();
                percent = totalBytes != 0 ? currentBytes * 100 / totalBytes : 0;
                ret.put(TAG_PERCENT, EMPTY_STRING + percent);
                ret.put(TAG_CURRENT_BYTES, EMPTY_STRING + currentBytes);
                ret.put(TAG_TOTAL_BYTES, EMPTY_STRING + totalBytes);
            }
        } else if (getMySessionFileItems(request) != null) {
            if (fieldname == null) {
                ret.put(TAG_FINISHED, RESP_OK);
                log.debug(String.format(MSG_S_GET_UPLOAD_STATUS_S_FINISHED_WITH_FILES_S, request.getSession().getId(),
                        request.getQueryString(), session.getAttribute(getSessionFilesKey(request))));
            } else {
                List<org.apache.commons.fileupload.FileItem> sessionFiles = getMySessionFileItems(request);
                for (org.apache.commons.fileupload.FileItem file : sessionFiles) {
                    if (!file.isFormField() && file.getFieldName().equals(fieldname)) {
                        ret.put(TAG_FINISHED, "ok");
                        ret.put(Constants.PARAM_FILENAME, fieldname);
                        log.debug(String.format(MSG_S_GET_UPLOAD_STATUS_S_FINISHED_WITH_FILES_S, request.getSession().getId(),
                                fieldname, session.getAttribute(getSessionFilesKey(request))));
                    }
                }
            }
        } else {
            log.debug(String.format(MSG_S_GET_UPLOAD_STATUS_NO_LISTENER_IN_SESSION, request.getSession().getId()));
            ret.put("wait", "listener is null");
        }
        if (ret.containsKey(TAG_FINISHED)) {
            removeCurrentListener(request);
        }
        PER_THREAD_REQUEST.set(null);
        return ret;
    }

    /**
     * This method parses the submit action, puts in session a listener where the
     * progress status is updated, and eventually stores the received data in
     * the user session.
     *
     * returns null in the case of success or a string with the error
     *
     */
    protected String parsePostRequest(HttpServletRequest request, HttpServletResponse response) {

        /* Do not allow override upload parameters
        try {
            String delay = request.getParameter(PARAM_DELAY);
            String maxFilesize = request.getParameter(PARAM_MAX_FILE_SIZE);
            maxSize = maxFilesize != null && maxFilesize.matches("[0-9]*") ? Long.parseLong(maxFilesize) : maxSize;
            uploadDelay = Integer.parseInt(delay);
        } catch (Exception e) {
            // TODO: Nothing
        } */

        HttpSession session = request.getSession();

        log.debug(String.format(MSG_S_NEW_UPLOAD_REQUEST_RECEIVED, request.getSession().getId()));

        AbstractUploadListener listener = getCurrentListener(request);
        if (listener != null) {
            if (listener.isFrozen() || listener.isCanceled() || listener.getPercent() >= 100) {
                removeCurrentListener(request);
            } else {
                String error = UploadUtils.getMessage(KEY_BUSY);
                log.error(String.format("(%s) %s", request.getSession().getId(), error));
                return error;
            }
        }

        // Create a file upload progress listener, and put it in the user session,
        // so the browser can use ajax to query status of the upload process
        listener = createNewListener(request);

        List<org.apache.commons.fileupload.FileItem> uploadedItems;
        try {

            // Call to a method which the user can override
            checkRequest(request);

            // Create the factory used for uploading files,
            org.apache.commons.fileupload.FileItemFactory factory = getFileItemFactory(getContentLength(request));
            org.apache.commons.fileupload.servlet.ServletFileUpload uploader = new org.apache.commons.fileupload.servlet.ServletFileUpload(factory);
            uploader.setSizeMax(maxSize);
            uploader.setFileSizeMax(maxFileSize);
            uploader.setProgressListener(listener);

            // Receive the files
            log.info(String.format(MSG_S_PARSING_HTTP_POST_REQUEST, request.getSession().getId()));
            uploadedItems = uploader.parseRequest(request);
            session.removeAttribute(getSessionLastFilesKey(request));
            log.info(String.format(MSG_S_PARSED_REQUEST_D_ITEMS_RECEIVED, request.getSession().getId(), uploadedItems.size()));

            // Received files are put in session
            List<org.apache.commons.fileupload.FileItem> sessionFiles = getMySessionFileItems(request);
            if (sessionFiles == null) {
                sessionFiles = new ArrayList<>();
            }

            String error = EMPTY_STRING;
            if (uploadedItems.size() > 0) {
                sessionFiles.addAll(uploadedItems);
                String msg = EMPTY_STRING;
                for (org.apache.commons.fileupload.FileItem i : sessionFiles) {
                    msg += String.format(MSG_S_S_D_BYTES, i.getFieldName(), i.getName(), i.getSize());
                }
                log.debug(String.format(MSG_S_PUTING_ITEMS_IN_SESSION_S, request.getSession().getId(), msg));
                session.setAttribute(getSessionFilesKey(request), sessionFiles);
                session.setAttribute(getSessionLastFilesKey(request), uploadedItems);
            }
            return error.length() > 0 ? error : null;

            // So much silly questions in the list about this issue.
        } catch (LinkageError e) {
            log.error(String.format(MSG_S_EXCEPTION_S, request.getSession().getId(), e.getMessage()), e);
            RuntimeException ex = new UploadActionException(UploadUtils.getMessage(KEY_RESTRICTED, e.getMessage()), e);
            listener.setException(ex);
            throw ex;
        } catch (org.apache.commons.fileupload.FileUploadBase.SizeLimitExceededException e) {
            RuntimeException ex = new UploadSizeLimitException(e.getPermittedSize(), e.getActualSize());
            listener.setException(ex);
            throw ex;
        } catch (UploadSizeLimitException | UploadCanceledException | UploadTimeoutException e) {
            listener.setException(e);
            throw e;
        } catch (Throwable e) {
            log.error(String.format(MSG_S_EXCEPTION_S, request.getSession().getId(), e.getMessage()), e);
            RuntimeException ex = new UploadException(e);
            listener.setException(ex);
            throw ex;
        }
    }

    /**
     * Remove the listener active in this session.
     *
     * @param request
     */
    protected void removeCurrentListener(HttpServletRequest request) {
        AbstractUploadListener listener = getCurrentListener(request);
        if (listener != null) {
            listener.remove();
        }
    }

}
