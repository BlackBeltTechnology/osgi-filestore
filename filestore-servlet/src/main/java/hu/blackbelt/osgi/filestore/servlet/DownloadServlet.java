package hu.blackbelt.osgi.filestore.servlet;

import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.security.api.DownloadClaim;
import hu.blackbelt.osgi.filestore.security.api.Token;
import hu.blackbelt.osgi.filestore.security.api.TokenValidator;
import hu.blackbelt.osgi.filestore.security.api.exceptions.InvalidTokenException;
import hu.blackbelt.osgi.filestore.servlet.exceptions.MissingParameterException;
import hu.blackbelt.osgi.filestore.servlet.exceptions.TokenRequiredException;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;

import static hu.blackbelt.osgi.filestore.servlet.Constants.*;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.*;
import static hu.blackbelt.osgi.filestore.servlet.utils.HttpUtils.processCORS;

/**
 * Download servlet.
 */
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, service = Servlet.class)
@Designate(ocd = DownloadServlet.Config.class)
@Slf4j
public class DownloadServlet extends HttpServlet {

    @ObjectClassDefinition()
    public @interface Config {

        @AttributeDefinition(required = false, name = "CORS domain regular expression")
        String corsDomainRegex() default "^$";

        @AttributeDefinition(name = "Servlet path")
        String servletPath();

        @AttributeDefinition(required = false, name = "Token required", description = "Enforce token check", type = AttributeType.BOOLEAN)
        boolean tokenRequired() default false;
    }

    private String corsDomainsRegex;
    private String servletPath;
    private boolean tokenRequired;

    @Reference
    private HttpService httpService;

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    FileStoreService fileStoreService;

    @Reference(policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
    TokenValidator tokenValidator;

    @Activate
    @SneakyThrows({ ServletException.class, NamespaceException.class })
    protected void activate(DownloadServlet.Config config) {
        corsDomainsRegex = config.corsDomainRegex();
        servletPath = config.servletPath();
        tokenRequired = config.tokenRequired();

        httpService.registerServlet(servletPath, this, null, null);
    }

    @Deactivate
    protected void deactivate() {
        httpService.unregister(servletPath);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        processCORS(request, response, corsDomainsRegex);
        super.service(request, response);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        PER_THREAD_REQUEST.set(request);
        try {
            if (tokenRequired && tokenValidator == null) {
                throw new IllegalStateException(UploadUtils.getMessage(KEY_NOT_READY));
            }
            final Token<DownloadClaim> downloadToken;
            if (tokenValidator != null) {
                downloadToken = tokenValidator.parseDownloadToken(request.getHeader(HEADER_TOKEN));
                if (downloadToken == null) {
                    throw new TokenRequiredException(UploadUtils.getMessage(KEY_MISSING_TOKEN, HEADER_TOKEN));
                }
            } else {
                downloadToken = null;
            }
            String fileId = request.getParameter(PARAM_FILE_ID);

            if (downloadToken != null) {
                final String tokenFileId = (String) downloadToken.get(DownloadClaim.FILE_ID);
                if (fileId != null && !Objects.equals(fileId, tokenFileId)) {
                    throw new InvalidTokenException(null);
                } else if (fileId == null) {
                    fileId = tokenFileId;
                }
            } else if (fileId == null) {
                throw new MissingParameterException(UploadUtils.getMessage(KEY_MISSING_PARAMETER, PARAM_FILE_ID));
            }
            String fileName = fileStoreService.getFileName(fileId);
            String contentType = fileStoreService.getMimeType(fileId);
            long size = fileStoreService.getSize(fileId);
            final Optional<String> disposition = Optional.ofNullable(downloadToken).map(t -> (String) t.get(DownloadClaim.DISPOSITION));
            if (fileName == null && downloadToken != null) {
                fileName = (String) downloadToken.get(DownloadClaim.FILE_NAME);
            }
            if (contentType == null && downloadToken != null) {
                contentType = (String) downloadToken.get(DownloadClaim.FILE_MIME_TYPE);
            }
            if (fileName != null) {
                response.setHeader("Content-Disposition", disposition.orElse("attachment") + "; filename=\"" + fileName + "\"");
            } else {
                response.setHeader("Content-Disposition", disposition.orElse("attachment"));
            }
            if (contentType != null) {
                response.setContentType(contentType);
            }
            if (size < Integer.MAX_VALUE && size >= 0) {
                response.setContentLength((int) size);
            }

            final InputStream in = fileStoreService.get(fileId);
            final OutputStream out = response.getOutputStream();

            copyFromInputStreamToOutputStream(in, out);
        } catch (TokenRequiredException e) {
            response.setContentType(MIMETYPE_TEXT_PLAIN);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } catch (InvalidTokenException e) {
            response.setContentType(MIMETYPE_TEXT_PLAIN);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, UploadUtils.getMessage(KEY_INVALID_TOKEN)));
        } catch (MissingParameterException e) {
            response.setContentType(MIMETYPE_TEXT_PLAIN);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } catch (Exception e) {
            response.setContentType(MIMETYPE_TEXT_PLAIN);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error(String.format(MSG_S_EXCEPTION_S, request.getSession().getId(), e.getMessage()), e);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } finally {
            PER_THREAD_REQUEST.set(null);
        }
    }
}
