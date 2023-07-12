package hu.blackbelt.osgi.filestore.rdbms.helper;

/*-
 * #%L
 * JUDO framework RDBMS filestore
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

public final class FilestoreHelper {
    public static final String FILENAME_FIELD = "FILENAME";
    public static final String MIME_TYPE_FIELD = "MIME_TYPE";
    public static final String DATA_FIELD = "DATA";
    public static final String SIZE_FIELD = "SIZE";
    public static final String CREATE_TIME_FIELD = "CREATE_TIME";

    public static final String NOT_FOUND_MESSAGE = "No file found with the given id.";

    public static String count(String tableName, String fileId) {
        return "SELECT COUNT(*) FROM " + tableName + " WHERE FILE_ID = '" + fileId + "'";
    }

    public static String insert(String tableName) { return "INSERT INTO " + tableName + " (FILE_ID, FILENAME, MIME_TYPE, SIZE, CREATE_TIME, DATA)"
            + " VALUES(?, ?, ?, ?, ?, ?);";}

    public static String read(String tableName, String fileId, String colName) {
        return "SELECT " + colName + " FROM " + tableName + " WHERE FILE_ID = '" + fileId + "'";
    }

    public static String meta(String tableName, String fileId) {
        return "SELECT " + FILENAME_FIELD + "," + MIME_TYPE_FIELD + "," + SIZE_FIELD + "," + CREATE_TIME_FIELD
                + " FROM " + tableName + " WHERE FILE_ID = '" + fileId + "'";
    }

    private FilestoreHelper() {
    }
}
