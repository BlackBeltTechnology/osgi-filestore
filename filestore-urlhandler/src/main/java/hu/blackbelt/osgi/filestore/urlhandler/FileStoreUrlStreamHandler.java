package hu.blackbelt.osgi.filestore.urlhandler;

import hu.blackbelt.osgi.filestore.api.FileStoreService;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.osgi.service.url.URLStreamHandlerService;

import java.net.URL;
import java.net.URLConnection;

public class FileStoreUrlStreamHandler extends AbstractURLStreamHandlerService implements URLStreamHandlerService {

    private final FileStoreService fileStoreService;

    public FileStoreUrlStreamHandler(FileStoreService fileStoreService) {
        this.fileStoreService = fileStoreService;
    }

    @Override
    public URLConnection openConnection(URL url) {
        String uuid = url.toString().substring((fileStoreService.getProtocol() + ":").length(), url.toString().indexOf("-"));
        return new FileStoreUrlConnection(url, fileStoreService, uuid);
    }
}
