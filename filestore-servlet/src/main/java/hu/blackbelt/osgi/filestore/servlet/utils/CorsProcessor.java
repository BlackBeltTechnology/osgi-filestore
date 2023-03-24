package hu.blackbelt.osgi.filestore.servlet.utils;

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

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

import static hu.blackbelt.osgi.filestore.servlet.Constants.*;

@Slf4j
@Builder
public class CorsProcessor {

    @Getter
    @Builder.Default
    private final Collection<String> allowOrigins = Collections.singleton(ALL);

    @Builder.Default
    private final boolean allowCredentials = true;

    @Builder.Default
    private final Collection<String> allowHeaders = Arrays.asList(HEADER_CONTENT_TYPE, HEADER_ORIGIN, HEADER_ACCEPT, HEADER_AUTHORIZATION, HEADER_TOKEN);

    @Builder.Default
    private final Collection<String> exposeHeaders = Arrays.asList(HEADER_CONTENT_TYPE);

    @Builder.Default
    private final int maxAge = -1;

    @Builder.Default
    private final int preflightErrorStatus = CORS_PREFLIGHT_ERROR_CODE;

    public boolean process(HttpServletRequest request, HttpServletResponse response, Collection<String> acceptedMethods) {
        response.setHeader(HEADER_ALLOW, acceptedMethods.stream().collect(Collectors.joining(",")));
        if (METHOD_OPTIONS.equals(request.getMethod())) {
            preflightRequest(request, response, acceptedMethods);
            return false;
        } else if (acceptedMethods.contains(request.getMethod())) {
            return handleRequest(request, response);
        } else {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return false;
        }
    }

    private boolean handleRequest(HttpServletRequest request, HttpServletResponse response) {
        List<String> headerOriginValues = getHeaderValues(request, HEADER_ORIGIN);
        if (headerOriginValues == null || headerOriginValues.isEmpty()) {
            return true;
        }

        if (!allowOrigins.contains(ALL) && !allowOrigins.containsAll(headerOriginValues)) {
            log.debug("Origin not allowed: {}", headerOriginValues);
            createPreflightResponse(response, false);
            return false;
        }

        response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, headerOriginValues.get(0));
        response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS, String.valueOf(allowCredentials));
        if (!exposeHeaders.isEmpty()) {
            response.addHeader(HEADER_ACCESS_CONTROL_EXPOSE_HEADERS, exposeHeaders.stream().collect(Collectors.joining(",")));
        }

        return true;
    }

    private void preflightRequest(HttpServletRequest request, HttpServletResponse response, Collection<String> acceptedMethods) {
        List<String> headerOriginValues = getHeaderValues(request, HEADER_ORIGIN);
        if (headerOriginValues == null || headerOriginValues.size() != 1) {
            return;
        }
        String origin = headerOriginValues.get(0);

        List<String> requestMethodValues = getHeaderValues(request, HEADER_ACCESS_CONTROL_REQUEST_METHOD);
        if (requestMethodValues == null || requestMethodValues.size() != 1) {
            log.debug("Missing method in request");
            createPreflightResponse(response, false);
            return;
        }

        String requestMethod = requestMethodValues.get(0);
        if (!acceptedMethods.contains(requestMethod)) {
            log.debug("Method not allowed: {}", requestMethodValues);
            createPreflightResponse(response, false);
            return;
        }

        if (!allowOrigins.contains(ALL) && !allowOrigins.contains(origin)) {
            log.debug("Origin not allowed: {}", origin);
            createPreflightResponse(response, false);
            return;
        }

        List<String> requestHeaders = getHeaderValues(request, HEADER_ACCESS_CONTROL_REQUEST_HEADERS);
        if (!checkAllowHeaders(requestHeaders)) {
            log.debug("Headers not allowed: {}", requestHeaders);
            createPreflightResponse(response, false);
            return;
        }

        response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, headerOriginValues.get(0));
        response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_METHODS, requestMethod);
        if (!requestHeaders.isEmpty()) {
            response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_HEADERS, requestHeaders.stream().collect(Collectors.joining(",")));
        }
        response.addHeader(HEADER_ACCESS_CONTROL_MAX_AGE, String.valueOf(maxAge));
        response.addHeader(HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS, String.valueOf(allowCredentials));
        createPreflightResponse(response, true);
    }

    private void createPreflightResponse(HttpServletResponse response, boolean passed) {
        int status = passed ? 200 : preflightErrorStatus;
        response.setStatus(status);
    }

    private boolean checkAllowHeaders(List<String> aHeaders) {
        if (allowHeaders.contains(ALL)) {
            return true;
        }
        Set<String> actualHeadersSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        actualHeadersSet.addAll(allowHeaders);
        return actualHeadersSet.containsAll(aHeaders);
    }

    private static List<String> getHeaderValues(HttpServletRequest request, String key) {
        return Collections.list((Enumeration<String>)request.getHeaders(key)).stream()
                .flatMap(v -> Arrays.asList(v.split("\\s*,\\s*")).stream())
                .collect(Collectors.toList());
    }
}
