package hu.blackbelt.osgi.filestore.servlet;

public final class Constants {
    public static final long serialVersionUID = 2740693677625051632L;

    public static final String EMPTY_STRING = "";

    public static final String TAG_KEY = "key";
    public static final String TAG_CTYPE = "ctype";
    public static final String TAG_CURRENT_BYTES = "currentBytes";
    public static final String TAG_DELETED = "deleted";
    public static final String TAG_FIELD = "field";
    public static final String TAG_NAME = "name";
    public static final String TAG_PERCENT = "percent";
    public static final String TAG_SIZE = "size";
    public static final String TAG_TOTAL_BYTES = "totalBytes";
    public static final String TAG_OK = "ok";
    public static final String TAG_FILE = "file";
    public static final String TAG_FILES = "files";
    public static final String TAG_VALUE = "value";
    public static final String TAG_PARAM = "parameter";
    public static final String TAG_PARAMS = "parameters";

    public static final String TAG_MSG_START = "%%%INI%%%";
    public static final String TAG_MSG_END = "%%%END%%%";
    public static final String TAG_MSG_GT = "^^^@@";
    public static final String TAG_MSG_LT = "@@^^^";

    public static final String PARAM_CANCEL = "cancel";
    public static final String PARAM_CLEAN = "clean";
    public static final String PARAM_DELAY = "delay";
    public static final String PARAM_FILENAME = "filename";
    public static final String PARAM_REMOVE = "remove";
    public static final String PARAM_SESSION = "new_session";
    public static final String PARAM_SHOW = "show";
    public static final String PARAM_KEEP_SESSION = "keep_session";

    public static final String MULTI_SUFFIX = "[]";

    public static final String RESP_OK = TAG_OK;
    public static final String PARAM_MAX_FILE_SIZE = "maxFileSize";

    protected static final int DEFAULT_REQUEST_LIMIT_KB = 50 * 1024 * 1024;
    protected static final int DEFAULT_SLOW_DELAY_MILLIS = 0;

    public static final String XML_DELETED_TRUE = "<deleted>true</deleted>";
    public static final String XML_ERROR_ITEM_NOT_FOUND = "<error>item not found</error>";
    public static final String SESSION_FILES = "FILES";
    public static final String SESSION_LAST_FILES = "LAST_FILES";
    public static final String XML_TPL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<response>%%MESSAGE%%</response>\n";

    public static final String HEADER_ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    public static final String HEADER_ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";
    public static final String HEADER_ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    public static final String HEADER_ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";

    public static final String HEADER_ALLOW = "Allow";
    public static final String METHOD_GET = "GET";
    public static final String METHOD_OPTIONS = "OPTIONS";
    public static final String ALLOW_VALUE = METHOD_GET + ":" + " HEAD, POST, PUT, DELETE, TRACE, OPTIONS";

    public static final String HEADER_TOKEN = "X-Token";
    public static final String PARAM_FILE_ID = "id";

    public static final String MIMETYPE_TEXT_PLAIN = "text/plain";
    public static final String MIMETYPE_TEXT_HTML = "text/html";
    public static final String MIMETYPE_APPLICATION_JSON = "application/json";

    public static final String HEADER_ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    public static final String HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";

    public static final String TRUE = "true";
    public static final String UNKNOWN = "unknown";
    public static final String LT = "<";
    public static final String GT = ">";
    public static final String SLASH = "/";
    public static final String NEW_LINE = "\n";

    public static final String TAG_ERROR = "error";
    public static final String TAG_FINISHED = "finished";
    public static final String TAG_CANCELED = "canceled";

    public static final String MSG_TIMEOUT_RECEIVING_FILE = "timeout receiving file";
    public static final String MSG_S_EXCEPTION_S = "(%s) Exception -> %s";
    public static final String MSG_S_GET_UPLOAD_STATUS_S_FINISHED_WITH_FILES_S = "(%s) getUploadStatus: %s finished with files: %s";
    public static final String MSG_S_GET_UPLOAD_STATUS_S_FINISHED_WITH_ERROR_S = "(%s) getUploadStatus: %s finished with error: %s";
    public static final String MSG_S_GET_UPLOAD_STATUS_S_CANCELED_BY_THE_USER_AFTER_D_BYTES
            = "(%s) getUploadStatus: %s canceled by the user after %d Bytes";
    public static final String MSG_S_GET_UPLOAD_STATUS_NO_LISTENER_IN_SESSION = "(%s) getUploadStatus: no listener in session";
    public static final String MSG_S_NEW_UPLOAD_REQUEST_RECEIVED = "(%s) new upload request received.";
    public static final String MSG_S_PARSING_HTTP_POST_REQUEST = "(%s) parsing HTTP POST request";
    public static final String MSG_S_PARSED_REQUEST_D_ITEMS_RECEIVED = "(%s) parsed request: %d items received.";
    public static final String MSG_S_S_D_BYTES = "%s => %s (%d bytes) :";
    public static final String MSG_S_PUTING_ITEMS_IN_SESSION_S = "(%s) puting items in session: %s";
    public static final String MSG_CHECK_CORS_ERROR_ORIGIN_S_DOES_NOT_MATCH_S = "checkCORS error Origin: %s does not match: %s";
    public static final String MSG_S_CLEAN_LISTENER = "(%s) cleanListener";
    public static final String MSG_S_NEW_SESSION = "(%s) New session";
    public static final String MSG_S_PROCESING_A_REQUEST_WITH_SIZE_D_BYTES = "(%s) procesing a request with size: %d bytes.";
    public static final String MSG_S_CANCELLING_UPLOAD = "(%s) cancelling Upload";
    public static final String MSG_INIT_MAX_SIZE_D_UPLOAD_DELAY_D_CORS_REGEX_S = "init: maxSize=%d uploadDelay=%d corsRegex=%s";
    public static final String MSG_S_REMOVE_SESSION_FILE_ITEMS_REMOVE_DATA_S = "(%s) removeSessionFileItems: removeData=%s";
    public static final String MSG_S_REMOVE_UPLOADED_FILE_S_S_D = "(%s) removeUploadedFile: %s %s %d";
    public static final String MSG_S_REMOVE_UPLOADED_FILE_S_NOT_IN_SESSION = "(%s) removeUploadedFile: %s not in session.";
    public static final String MSG_S_GET_UPLOADED_FILE_S_RETURNING_S_S_D_BYTES = "(%s) getUploadedFile: %s returning: %s : %s %d bytes";
    public static final String MSG_S_GET_UPLOADED_FILE_S_FILE_ISN_T_IN_SESSION = "(%s) getUploadedFile: %s file isn't in session.";

    public static final String XML_SESSIONID_S_SESSIONID = "<sessionid>%s</sessionid>";
    public static final String XML_CANCELED_S_CANCELED = "<canceled>%s</canceled>";
    public static final String XML_FINISHED_S_FINISHED = "<finished>%s</finished>";
    public static final String XML_ERROR_S_ERROR = "<error>%s</error>";

    public static final String KEY_SERVER_ERROR = "server_error";
    public static final String KEY_BUSY = "busy";
    public static final String KEY_RESTRICTED = "restricted";
    public static final String KEY_MISSING_TOKEN = "missing_token";
    public static final String KEY_MISSING_PARAMETER = "missing_parameter";
    public static final String KEY_INVALID_MIME_TYPE = "invalid_mime_type";
    public static final String KEY_INVALID_TOKEN = "invalid_token";

    private Constants() { }
}
