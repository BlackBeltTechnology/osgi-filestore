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

import hu.blackbelt.osgi.filestore.security.api.*;
import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.servlet.exceptions.*;
import hu.blackbelt.osgi.filestore.servlet.utils.CorsProcessor;
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
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static hu.blackbelt.osgi.filestore.servlet.Constants.*;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.*;

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

        @AttributeDefinition(name = "Servlet path")
        String servletPath();

        @AttributeDefinition(required = false, name = "Token required", description = "Enforce token check", type = AttributeType.BOOLEAN)
        boolean tokenRequired() default false;

        @AttributeDefinition(name = "CORS allow origin", description = "Comma-separated list of Access-Control-Allow-Origin")
        String cors_allowOrigin() default ALL;

        @AttributeDefinition(name = "CORS allow headers", description = "Access-Control-Allow-Credentials", type = AttributeType.BOOLEAN)
        boolean cors_allowCredentials() default true;

        @AttributeDefinition(name = "CORS allow headers", description = "Comma-separated list of Access-Control-Allow-Headers")
        String cors_allowHeaders() default HEADER_CONTENT_TYPE + "," + HEADER_ORIGIN + "," + HEADER_ACCEPT + "," + HEADER_AUTHORIZATION;

        @AttributeDefinition(name = "CORS expose headers", description = "Comma-separated list of Access-Control-Expose-Headers")
        String cors_exposeHeaders() default HEADER_CONTENT_TYPE + "," + HEADER_CONTENT_DISPOSITION;

        @AttributeDefinition(name = "CORS max age", description = "Access-Control-Max-Age")
        int cors_maxAge() default -1;

        @AttributeDefinition(name = "CORS preflight error code", description = "HTTP status code returned by failed prefligth requests", type = AttributeType.INTEGER)
        int cors_prefligthErrorStatus() default CORS_PREFLIGHT_ERROR_CODE;
    }

    protected long maxSize;
    protected long maxFileSize;
    protected int noDataTimeout;
    protected int uploadDelay;
    private CorsProcessor corsProcessor = CorsProcessor.builder().build();
    private String servletPath;
    private boolean tokenRequired;

    @Reference
    private HttpService httpService;

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    FileStoreService fileStoreService;

    @Reference(policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
    TokenValidator tokenValidator;

    @Reference(policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
    TokenIssuer tokenIssuer;

    public UploadServlet() {
    }

    @Activate
    @SneakyThrows({ServletException.class, NamespaceException.class})
    protected void activate(Config config) {
        maxSize = config.maxSize();
        maxFileSize = config.maxFileSize();
        noDataTimeout = config.noDataTimeout();
        uploadDelay = config.slowUploads();
        corsProcessor = CorsProcessor.builder()
                .allowOrigins(config.cors_allowOrigin() != null ? Arrays.asList(config.cors_allowOrigin().split("\\s*,\\s*")) : Collections.emptyList())
                .allowCredentials(config.cors_allowCredentials())
                .allowHeaders(Stream
                        .concat(
                                (config.cors_allowHeaders() != null ? Arrays.asList(config.cors_allowHeaders().split("\\s*,\\s*")) : Collections.<String>emptyList()).stream(),
                                Arrays.asList(HEADER_TOKEN).stream())
                        .collect(Collectors.toSet()))
                .exposeHeaders(config.cors_exposeHeaders() != null ? Arrays.asList(config.cors_exposeHeaders().split("\\s*,\\s*")) : Collections.emptyList())
                .maxAge(config.cors_maxAge())
                .preflightErrorStatus(config.cors_prefligthErrorStatus())
                .build();
        servletPath = config.servletPath();
        tokenRequired = config.tokenRequired();

        log.info(String.format(MSG_INIT_MAX_SIZE_D_UPLOAD_DELAY_D_CORS_REGEX_S, maxSize, uploadDelay, corsProcessor.getAllowOrigins()));
        httpService.registerServlet(servletPath, this, getInitParams(servletPath), null);
    }

    @Deactivate
    protected void deactivate() {
        httpService.unregister(servletPath);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (corsProcessor.process(request, response, Arrays.asList(METHOD_GET, METHOD_POST, METHOD_OPTIONS))) {
            super.service(request, response);
        }
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
     * @param request HTTP request
     * @param maxSize max size
     * @throws RuntimeException
     */
    public void checkRequest(HttpServletRequest request, long maxSize) {
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
        response.setContentType(MIMETYPE_TEXT_PLAIN);
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
     * <p>
     * The content of this xml document has a tag error in the case of error in
     * the upload process or the string OK in the case of success.
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        response.setContentType(MIMETYPE_TEXT_PLAIN);
        PER_THREAD_REQUEST.set(request);
        String error;
        try {
            if (tokenRequired && (tokenValidator == null || tokenIssuer == null)) {
                throw new IllegalStateException(UploadUtils.getMessage(KEY_NOT_READY));
            }
            final Token<UploadClaim> uploadToken;
            if (tokenValidator != null) {
                uploadToken = tokenValidator.parseUploadToken(request.getHeader(HEADER_TOKEN));
                if (uploadToken == null) {
                    throw new TokenRequiredException(UploadUtils.getMessage(KEY_MISSING_TOKEN, HEADER_TOKEN));
                }
            } else {
                uploadToken = null;
            }
            error = parsePostRequest(request, response, uploadToken != null ? (Long) UploadClaim.MAX_FILE_SIZE.convert(uploadToken.get(UploadClaim.MAX_FILE_SIZE)) : null);
            String postResponse = "";
            Map<String, String> stat = new HashMap<>();
            if (error != null && error.length() > 0) {
                postResponse = String.format("{\"error\":\"%s\"}", error);
            } else {
                List<String> allFiles = new ArrayList<>();
                for (org.apache.commons.fileupload.FileItem f : getMyLastReceivedFileItems(request)) {
                    final Collection<String> expectedMimeTypeList = uploadToken != null && uploadToken.get(UploadClaim.FILE_MIME_TYPE_LIST) != null ? Arrays.asList(((String) uploadToken.get(UploadClaim.FILE_MIME_TYPE_LIST)).split("\\s*,\\s*")) : Collections.emptyList();
                    if (!expectedMimeTypeList.isEmpty()) {
                        final String uploadedMimeType = f.getContentType();
                        if (f.getContentType() == null || expectedMimeTypeList.stream().noneMatch(m -> m.equals(uploadedMimeType) || m.equals("*/*") || m.endsWith("/*") && uploadedMimeType.startsWith(m.substring(0, m.length() - 1)))) {
                            error = UploadUtils.getMessage(KEY_INVALID_MIME_TYPE, uploadedMimeType, expectedMimeTypeList);
                            allFiles.add(String.format("{\"field\":\"%s\",\"name\":\"%s\",\"ctype\":\"%s\",\"size\":%d,\"error\":\"%s\"}",
                                    f.getFieldName(), f.getName(), f.getContentType(), f.getSize(), error));
                            continue;
                        }
                    }
                    try (InputStream data = f.getInputStream()) {
                        String id = fileStoreService.put(data, f.getName(), f.getContentType());
                        URL url = fileStoreService.getAccessUrl(id);
                        if (tokenIssuer != null) {
                            String tokenString = tokenIssuer.createDownloadToken(Token.<DownloadClaim>builder()
                                    .jwtClaim(DownloadClaim.FILE_ID, id)
                                    .jwtClaim(DownloadClaim.FILE_NAME, f.getName())
                                    .jwtClaim(DownloadClaim.FILE_SIZE, f.getSize())
                                    .jwtClaim(DownloadClaim.FILE_MIME_TYPE, f.getContentType())
                                    .jwtClaim(DownloadClaim.CONTEXT, uploadToken != null ? uploadToken.get(UploadClaim.CONTEXT) : null)
                                    .build());
                            allFiles.add(String.format("{\"field\":\"%s\",\"id\":\"%s\",\"name\":\"%s\",\"url\":\"%s\",\"ctype\":\"%s\",\"size\":%d,\"token\":\"%s\"}",
                                    f.getFieldName(), id, f.getName(), url.toString(), f.getContentType(), f.getSize(), tokenString));
                        } else {
                            allFiles.add(String.format("{\"field\":\"%s\",\"id\":\"%s\",\"name\":\"%s\",\"url\":\"%s\",\"ctype\":\"%s\",\"size\":%d}",
                                    f.getFieldName(), id, f.getName(), url.toString(), f.getContentType(), f.getSize()));
                        }
                    }
                }
                postResponse = "{\"files\":[" + String.join(",", allFiles) + "],\"finished\":\"ok\"}";
            }
            finish(request, postResponse, request.getParameter(PARAM_KEEP_SESSION) != null ? Boolean.parseBoolean(request.getParameter(PARAM_KEEP_SESSION)) : false);
            renderMessage(response, postResponse, MIMETYPE_APPLICATION_JSON);
        } catch (UploadCanceledException e) {
            response.setStatus(HttpServletResponse.SC_GONE);
            renderJsonResponse(request, response, String.format(XML_CANCELED_S_CANCELED, TRUE));
        } catch (UploadTimeoutException e) {
            response.setStatus(HttpServletResponse.SC_REQUEST_TIMEOUT);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, MSG_TIMEOUT_RECEIVING_FILE));
        } catch (UploadSizeLimitException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } catch (TokenRequiredException e) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } catch (Exception e) {
            log.error(String.format(MSG_S_EXCEPTION_S, request.getSession().getId(), e.getMessage()), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } finally {
            PER_THREAD_REQUEST.set(null);
        }
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
    protected void finish(HttpServletRequest request, String postResponse, boolean keepSession) {
        AbstractUploadListener listener = getCurrentListener(request);
        if (listener != null) {
            listener.setFinished(postResponse);
        }
        if (!keepSession) {
            removeSessionFileItems(request);
            removeCurrentListener(request);
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
        log.info("GET UPLOAD STATUS...");
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
        log.info("  - listener: {}", listener);
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
            log.info("  - file items: {}", getMySessionFileItems(request));
            if (fieldname == null) {
                ret.put(TAG_FINISHED, RESP_OK);
                log.debug(String.format(MSG_S_GET_UPLOAD_STATUS_S_FINISHED_WITH_FILES_S, request.getSession().getId(),
                        request.getQueryString(), session.getAttribute(getSessionFilesKey(request))));
            } else {
                List<org.apache.commons.fileupload.FileItem> sessionFiles = getMySessionFileItems(request);
                for (org.apache.commons.fileupload.FileItem file : sessionFiles) {
                    if (!file.isFormField() && file.getFieldName().equals(fieldname)) {
                        ret.put(TAG_FINISHED, RESP_OK);
                        ret.put(Constants.PARAM_FILENAME, fieldname);
                        log.debug(String.format(MSG_S_GET_UPLOAD_STATUS_S_FINISHED_WITH_FILES_S, request.getSession().getId(),
                                fieldname, session.getAttribute(getSessionFilesKey(request))));
                    }
                }
            }
        } else {
            log.info("  - default");
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
     * <p>
     * returns null in the case of success or a string with the error
     */
    protected String parsePostRequest(HttpServletRequest request, HttpServletResponse response, Long maxFileSize) {

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
            checkRequest(request, maxFileSize != null && maxFileSize > maxSize ? maxFileSize : maxSize);

            // Create the factory used for uploading files,
            org.apache.commons.fileupload.FileItemFactory factory = getFileItemFactory(getContentLength(request));
            org.apache.commons.fileupload.servlet.ServletFileUpload uploader = new org.apache.commons.fileupload.servlet.ServletFileUpload(factory);
            uploader.setSizeMax(maxFileSize != null && maxFileSize > maxSize ? maxFileSize : maxSize);
            uploader.setFileSizeMax(maxFileSize != null ? maxFileSize : this.maxFileSize);
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

    private Dictionary getInitParams(String name) {
        Dictionary dictionary = new Hashtable();
        dictionary.put("servlet-name", "UploadServlet-" + name);
        return dictionary;
    }

}
