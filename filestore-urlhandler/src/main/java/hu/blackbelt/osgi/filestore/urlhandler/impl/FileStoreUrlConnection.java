package hu.blackbelt.osgi.filestore.urlhandler.impl;

import hu.blackbelt.osgi.filestore.api.FileStoreService;
import lombok.SneakyThrows;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

public class FileStoreUrlConnection extends URLConnection {

    final FileStoreService fileStoreService;
    final String uuid;

    protected FileStoreUrlConnection(URL url, FileStoreService fileStoreService, String uuid) {
        super(url);
        this.fileStoreService = fileStoreService;
        this.uuid = uuid;
    }

    @Override
    public void connect() {
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return fileStoreService.get(uuid);
    }

    @Override
    @SneakyThrows(IOException.class)
    public int getContentLength() {
        fileStoreService.getSize(uuid);
        return super.getContentLength();
    }

    @Override
    @SneakyThrows(IOException.class)
    public String getContentType() {
        return fileStoreService.getMimeType(uuid);
    }

    @Override
    @SneakyThrows(IOException.class)
    public long getDate() {
        return fileStoreService.getCreateTime(uuid).getTime();
    }
}
