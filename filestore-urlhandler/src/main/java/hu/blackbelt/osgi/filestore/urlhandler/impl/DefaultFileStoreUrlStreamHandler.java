package hu.blackbelt.osgi.filestore.urlhandler.impl;

import hu.blackbelt.osgi.filestore.api.FileStoreService;
import hu.blackbelt.osgi.filestore.urlhandler.FileStoreUrlStreamHandler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.osgi.service.url.URLStreamHandlerService;

import java.net.URL;
import java.net.URLConnection;

@Component(immediate = true)
@Designate(ocd = DefaultFileStoreUrlStreamHandler.Config.class)
public class DefaultFileStoreUrlStreamHandler extends AbstractURLStreamHandlerService implements URLStreamHandlerService, FileStoreUrlStreamHandler {

    @ObjectClassDefinition()
    public @interface Config {

        @AttributeDefinition(name = "URL handler protocol", description = "See org.osgi.service.url.URLConstants for details")
        String url_handler_protocol();
    }

    @Reference
    FileStoreService fileStoreService;

    private String protocol;

    @Activate
    void start(final Config config) {
        protocol = config.url_handler_protocol();
    }

    @Deactivate
    void stop() {
        protocol = null;
    }

    @Override
    public URLConnection openConnection(URL url) {
        String uuid = url.toString().substring((protocol + ":").length(), url.toString().indexOf("-"));
        return new FileStoreUrlConnection(url, fileStoreService, uuid);
    }

    @Override
    public String getProtocol() {
        return protocol;
    }
}
