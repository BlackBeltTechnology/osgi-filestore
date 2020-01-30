package hu.blackbelt.osgi.filestore.rdbms.helper;

public final class FilestoreHelper {
    public static final String FILENAME_FIELD = "FILENAME";
    public static final String MIME_TYPE_FIELD = "MIME_TYPE";
    public static final String DATA_FIELD = "DATA";
    public static final String SIZE_FIELD = "SIZE";
    public static final String CREATE_TIME_FIELD = "CREATE_TIME";

    public static final String NOT_FOUND_MESSAGE = "No file found with the given id.";

    public static final String INSERT_INTO =
            "INSERT INTO JUDO_FILESTORE(FILE_ID, FILENAME, MIME_TYPE, SIZE, CREATE_TIME, DATA)"
                    + "VALUES(?, ?, ?, ?, ?, ?);";

    public static String countString(String fileId) {
        return "SELECT COUNT(*) FROM JUDO_FILESTORE WHERE FILE_ID = '" + fileId + "'";
    }

    public static String readString(String fileId, String colName) {
        return "SELECT " + colName + " FROM JUDO_FILESTORE WHERE FILE_ID = '" + fileId + "'";
    }

    private FilestoreHelper() {
    }
}
