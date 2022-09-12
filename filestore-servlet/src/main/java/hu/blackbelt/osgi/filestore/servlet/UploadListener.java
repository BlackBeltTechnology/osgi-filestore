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

import hu.blackbelt.osgi.filestore.servlet.exceptions.UploadTimeoutException;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.Date;

import static hu.blackbelt.osgi.filestore.servlet.UploadUtils.getThreadLocalRequest;

/**
 * This is a File Upload Listener that is used by Apache Commons File Upload to
 * monitor the progress of the uploaded file.
 */
@Slf4j
public class UploadListener extends AbstractUploadListener {

    protected static final String ATTR_LISTENER = "LISTENER";
    private final long serialVersionUID = -6431275569719042836L;
    private final int watcherInterval = 5000;
    private int noDataTimeout = 20000;
    private TimeoutWatchDog watcher;

    /**
     * Default constructor.
     */
    public UploadListener(int sleepMilliseconds, long requestSize, int noDataTimeoutMillisec) {
        super(sleepMilliseconds, requestSize);
        noDataTimeout = noDataTimeoutMillisec;
        startWatcher();
    }


    public static AbstractUploadListener current(HttpServletRequest request) {
        return (AbstractUploadListener) request.getSession().getAttribute(ATTR_LISTENER);
    }

    public static AbstractUploadListener current(String sessionId) {
        return (AbstractUploadListener) session().getAttribute(ATTR_LISTENER);
    }

    /**
     * Upload servlet saves the current request as a ThreadLocal,
     * so it is accessible from any class.
     *
     * @return request of the current thread
     */
    private static HttpServletRequest request() {
        return getThreadLocalRequest();
    }

    /**
     * Returns current HttpSession.
     * @return current HttpSession
     */
    private static HttpSession session() {
        return request() != null ? request().getSession() : null;
    }

    /* (non-Javadoc)
     * @see AbstractUploadListener#remove()
     */
    public void remove() {
        log.info(sessionId + " remove: " + toString());
        if (session() != null) {
            session().removeAttribute(ATTR_LISTENER);
        }
        stopWatcher();
        saved = new Date();
    }

    /* (non-Javadoc)
     * @see AbstractUploadListener#save()
     */
    public void save() {
        if (session() != null) {
            session().setAttribute(ATTR_LISTENER, this);
        }
        saved = new Date();
        log.debug(sessionId + " save " + toString());
    }

    /* (non-Javadoc)
     * @see AbstractUploadListener#update(long, long, int)
     */
    @Override
    public void update(long done, long total, int item) {
        super.update(done, total, item);
        if (getPercent() >= 100) {
            stopWatcher();
        }
    }

    private void startWatcher() {
        if (watcher == null) {
            try {
                watcher = new TimeoutWatchDog(this);
                watcher.start();
            } catch (Exception e) {
                log.info(sessionId + " unable to create watchdog: " + e.getMessage());
            }
        }
    }

    private void stopWatcher() {
        if (watcher != null) {
            watcher.cancel();
        }
    }

    /**
     * A class which is executed in a new thread, so its able to detect
     * when an upload process is frozen and sets an exception in order to
     * be canceled.
     * This doesn't work in Google application engine
     */
    private class TimeoutWatchDog extends Thread implements Serializable {
        private static final long serialVersionUID = -649803529271569237L;

        AbstractUploadListener listener;
        private long lastBytesRead;
        private long lastData = (new Date()).getTime();

        public TimeoutWatchDog(AbstractUploadListener l) {
            listener = l;
        }

        public void cancel() {
            listener = null;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(watcherInterval);
            } catch (InterruptedException e) {
                log.error(sessionId + " TimeoutWatchDog: sleep Exception: " + e.getMessage());
            }
            if (listener != null) {
                if (listener.getBytesRead() > 0 && listener.getPercent() >= 100 || listener.isCanceled()) {
                    log.debug(sessionId + " TimeoutWatchDog: upload process has finished, stoping watcher");
                    listener = null;
                } else {
                    if (isFrozen()) {
                        log.info(sessionId + " TimeoutWatchDog: the recepcion seems frozen: "
                                + listener.getBytesRead() + "/" + listener.getContentLength() + " bytes ("
                                + listener.getPercent() + "%) ", new Throwable());
                        exception = new UploadTimeoutException("No new data received after " + noDataTimeout / 1000 + " seconds");
                    } else {
                        run();
                    }
                }
            }
        }

        private boolean isFrozen() {
            long now = (new Date()).getTime();
            if (bytesRead > lastBytesRead) {
                lastData = now;
                lastBytesRead = bytesRead;
            } else if (now - lastData > noDataTimeout) {
                return true;
            }
            return false;
        }
    }
}
