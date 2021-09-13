package hu.blackbelt.osgi.filestore.servlet;

import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.security.api.DownloadClaim;
import hu.blackbelt.osgi.filestore.security.api.Token;
import hu.blackbelt.osgi.filestore.security.api.TokenValidator;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.osgi.service.component.annotations.*;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.osgi.service.metatype.annotations.AttributeDefinition;
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
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.copyFromInputStreamToOutputStream;
import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.renderJsonResponse;
import static hu.blackbelt.osgi.filestore.servlet.utils.HttpUtils.processCORS;
import static java.util.Objects.requireNonNull;

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
    }

    public static final ThreadLocal<HttpServletRequest> PER_THREAD_REQUEST = new ThreadLocal<>();

    private String corsDomainsRegex;
    private String servletPath;

    @Reference
    private HttpService httpService;

    @Reference
    private FileStoreService fileStoreService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    TokenValidator tokenValidator;

    @Activate
    @SneakyThrows({ ServletException.class, NamespaceException.class })
    protected void activate(DownloadServlet.Config config) {
        corsDomainsRegex = config.corsDomainRegex();
        servletPath = config.servletPath();

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
            final Token<DownloadClaim> downloadToken;
            if (tokenValidator != null) {
                downloadToken = tokenValidator.parseDownloadToken(request.getHeader(HEADER_TOKEN));
                requireNonNull(downloadToken, "Missing token (" + HEADER_TOKEN + " HTTP header) to download file");
            } else {
                downloadToken = null;
            }
            final String fileId = request.getParameter(PARAM_FILE_ID);
            requireNonNull(fileId, "Missing fileId HTTP parameter");

            if (downloadToken != null) {
                final String tokenFileId = downloadToken.get(DownloadClaim.FILE_ID, String.class);
                if (!Objects.equals(fileId, tokenFileId)) {
                    throw new IllegalArgumentException("Invalid token");
                }
            }
            String fileName = fileStoreService.getFileName(fileId);
            String contentType = fileStoreService.getMimeType(fileId);
            long size = fileStoreService.getSize(fileId);
            final Optional<String> disposition = Optional.ofNullable(downloadToken).map(t -> t.get(DownloadClaim.DISPOSITION, String.class));
            if (fileName == null && downloadToken != null) {
                fileName = downloadToken.get(DownloadClaim.FILE_NAME, String.class);
            }
            if (contentType == null && downloadToken != null) {
                contentType = downloadToken.get(DownloadClaim.FILE_MIME_TYPE, String.class);
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
        } catch (Exception e) {
            log.error(String.format(MSG_S_EXCEPTION_S, request.getSession().getId(), e.getMessage()), e);
            renderJsonResponse(request, response, String.format(XML_ERROR_S_ERROR, e.getMessage()));
        } finally {
            PER_THREAD_REQUEST.set(null);
        }
    }
}
