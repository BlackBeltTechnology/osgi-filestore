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

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.util.Date;

/**
 *
 * Abstract class for file upload listeners used by apache-commons-fileupload to monitor
 * the progress of uploaded files.
 *
 * It is useful to implement UploadListeners that can be saved in different
 * ways.
 **
 */
@Slf4j
public abstract class AbstractUploadListener implements org.apache.commons.fileupload.ProgressListener, Serializable {

    protected static final long serialVersionUID = -6431275569719042836L;
//    protected static String className = AbstractUploadListener.class.getName().replaceAll("^.+\\.", "");
    protected static final int DEFAULT_SAVE_INTERVAL = 3000;
    protected Long bytesRead = 0L;
    protected Long contentLength = 0L;
    protected RuntimeException exception;
    protected boolean exceptionTrhown;
    protected int frozenTimeout = 60000;
    protected Date saved = new Date();
    protected String sessionId = "";
    protected int slowUploads;
    private String postResponse;

    public AbstractUploadListener(int sleepMilliseconds, long requestSize) {
        this();
        slowUploads = sleepMilliseconds;
        contentLength = requestSize;
        log.info(sessionId + " created new instance. (slow=" + sleepMilliseconds + ", requestSize=" + requestSize + ")");
        HttpServletRequest request = UploadUtils.getThreadLocalRequest();
        if (request != null) {
            sessionId = request.getSession().getId();
        }
        save();
    }


    private AbstractUploadListener() {
    }

    public static AbstractUploadListener current(String sessionId) {
        throw new RuntimeException("Implement the static method 'current' in your customized class");
    }

    /**
     * Get the bytes transfered so far.
     *
     * @return bytes
     */
    public long getBytesRead() {
        return bytesRead;
    }

    /**
     * Get the total bytes of the request.
     *
     * @return bytes
     */
    public long getContentLength() {
        return contentLength;
    }

    /**
     * Get the exception.
     */
    public RuntimeException getException() {
        return exception;
    }

    /**
     * Set the exception which cancels the upload.
     */
    public void setException(RuntimeException e) {
        exception = e;
        save();
    }

    /**
     * Return the percent done of the current upload.
     *
     * @return percent
     */
    public long getPercent() {
        return contentLength > 0 ? bytesRead * 100 / contentLength : 0;
    }

    /**
     * Return true if the process has been canceled due to an error or
     * by the user.
     *
     * @return boolean
     */
    public boolean isCanceled() {
        return exception != null;
    }

    public boolean isFinished() {
        return postResponse != null;
    }

    public void setFinished(String pPostResponse) {
        this.postResponse = pPostResponse;
        save();
    }

    /**
     * Return true if has lasted a long since the last data received.
     * by the user.
     *
     * @return boolean
     */
    public boolean isFrozen() {
        return getPercent() > 0 && getPercent() < 100 && (new Date()).getTime() - saved.getTime() > frozenTimeout;
    }

    /**
     * Remove itself from session or cache.
     */
    public abstract void remove();

    /**
     * Save itself in session or cache.
     */
    public abstract void save();

    public String toString() {
        return "total=" + getContentLength() + " done=" + getBytesRead() + " cancelled=" + isCanceled() + " finished="
                + isFinished() + " saved=" + saved;
    }

    /**
     * This method is called each time the server receives a block of bytes.
     */
    @SneakyThrows(InterruptedException.class)
    public void update(long done, long total, int item) {
        if (exceptionTrhown) {
            return;
        }

        // To avoid cache overloading, this object is saved when the upload starts,
        // when it has finished, or when the interval from the last save is significant.
        boolean save = bytesRead == 0 && done > 0 || done >= total || (new Date()).getTime() - saved.getTime() > DEFAULT_SAVE_INTERVAL;
        bytesRead = done;
        contentLength = total;
        if (save) {
            save();
        }

        // If other request has set an exception, it is thrown so the commons-fileupload's
        // parser stops and the connection is closed.
        if (isCanceled()) {
            String eName = exception.getClass().getName().replaceAll("^.+\\.", "");
            log.info(sessionId + " The upload has been canceled after " + bytesRead
                    + " bytes received, raising an exception (" + eName + ") to close the socket");
            exceptionTrhown = true;
            throw exception;
        }

        // Just a way to slow down the upload process and see the progress bar in fast networks.
        if (slowUploads > 0 && done < total) {
            Thread.sleep(slowUploads);
        }
    }

    public String getPostResponse() {
        return postResponse;
    }
}
