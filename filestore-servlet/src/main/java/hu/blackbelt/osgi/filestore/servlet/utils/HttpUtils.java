package hu.blackbelt.osgi.filestore.servlet.utils;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static hu.blackbelt.osgi.filestore.servlet.Constants.*;
import static hu.blackbelt.osgi.filestore.servlet.Constants.MSG_CHECK_CORS_ERROR_ORIGIN_S_DOES_NOT_MATCH_S;

@Slf4j
public class HttpUtils {

    public static boolean checkCORS(HttpServletRequest request, HttpServletResponse response, String corsDomainsRegex) {
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

    public static void processCORS(HttpServletRequest request, HttpServletResponse response, String corsDomainsRegex) {
        if (checkCORS(request, response, corsDomainsRegex) && METHOD_OPTIONS.equals(request.getMethod())) {
            String method = request.getHeader(HEADER_ACCESS_CONTROL_REQUEST_METHOD);
            if (method != null) {
                response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_METHODS, method);
                response.setHeader(HEADER_ALLOW, ALLOW_VALUE);
            }
            String headers = request.getHeader(HEADER_ACCESS_CONTROL_REQUEST_HEADERS);
            if (headers != null) {
                response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_HEADERS, headers);
            }
        }
    }
}
