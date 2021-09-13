package hu.blackbelt.osgi.filestore.itest.utils;

import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.MavenArtifactUrlReference;
import org.ops4j.pax.exam.options.ProvisionOption;

import java.io.File;
import java.net.URL;

import static java.lang.Boolean.getBoolean;
import static org.ops4j.pax.exam.CoreOptions.*;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.*;
import static org.ops4j.pax.exam.karaf.options.LogLevelOption.LogLevel.INFO;

public class TestUtil {
    public static MavenArtifactUrlReference karafStandardRepo() {
        return CoreOptions.maven().groupId("org.apache.karaf.features").artifactId("standard").versionAsInProject().classifier("features").type("xml");
    }

    public static MavenArtifactUrlReference karafUrl() {
        return CoreOptions.maven().groupId("org.apache.karaf").artifactId("apache-karaf").versionAsInProject().type("zip");
    }

    public static Option[] karafConfig(Class clazz) {
        return new Option[]{
                karafDistributionConfiguration()
                        .frameworkUrl(karafUrl())
                        .unpackDirectory(new File("target", "exam"))
                        .useDeployFolder(false),
                keepRuntimeFolder(),
                cleanCaches(true),
                logLevel(INFO),
                when(getBoolean("isDebugEnabled"))
                        .useOptions(new Option[]{vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")}),
                vmOption("-Dfile.encoding=UTF-8"),
                replaceConfigurationFile("etc/org.ops4j.pax.logging.cfg", getConfigFile(clazz, "/etc/org.ops4j.pax.logging.cfg")),
                when(getBoolean("useCustomSettings"))
                        .useOptions(new Option[]{replaceConfigurationFile("etc/org.ops4j.pax.url.mvn.cfg", getConfigFile(clazz, "/etc/org.ops4j.pax.url.mvn.cfg"))}),
                configureConsole().ignoreLocalConsole(),
                junitBundles(),
                provision(new ProvisionOption[]{mavenBundle()
                        .groupId("org.apache.servicemix.bundles")
                        .artifactId("org.apache.servicemix.bundles.hamcrest")
                        .versionAsInProject().start()})};
    }

    public static MavenArtifactUrlReference filestoreArtifact() {
        return maven().groupId("hu.blackbelt.osgi.filestore").artifactId("features").versionAsInProject().classifier("features").type("xml");
    }

    public static File getConfigFile(Class clazz, String path) {
        URL res = clazz.getResource(path);
        if (res == null) {
            throw new RuntimeException("Config resource " + path + " not found");
        } else {
            return new File(res.getFile());
        }
    }
}
