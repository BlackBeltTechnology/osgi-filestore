package hu.blackbelt.osgi.filestore.itest.utils;

import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.MavenArtifactUrlReference;
import org.ops4j.pax.exam.options.ProvisionOption;

import java.io.File;
import java.net.URL;

import static java.lang.Boolean.getBoolean;
import static org.ops4j.pax.exam.CoreOptions.*;
import static org.ops4j.pax.exam.OptionUtils.combine;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.*;
import static org.ops4j.pax.exam.karaf.options.LogLevelOption.LogLevel.INFO;

public class TestUtil {

    private static final String KARAF_VERSION = "4.2.11";
    private static final String FEATURE_REPO_HTTP_CLIENT_VERSION = "1.0.0";

    public static MavenArtifactUrlReference karafStandardRepo() {
        return CoreOptions.maven().groupId("org.apache.karaf.features").artifactId("standard").versionAsInProject().classifier("features").type("xml");
    }

    public static MavenArtifactUrlReference karafUrl() {
        return CoreOptions.maven().groupId("org.apache.karaf").artifactId("apache-karaf").versionAsInProject().type("zip");
    }

    public static Option[] configureVmOptions() {
        return options(
                systemProperty("pax.exam.osgi.`unresolved.fail").value("true"),
                vmOption("--add-reads=java.xml=java.logging"),
                vmOption("--add-exports=java.base/org.apache.karaf.specs.locator=java.xml,ALL-UNNAMED"),
                vmOption("--patch-module"),
                vmOption(
                        "java.base=lib/endorsed/org.apache.karaf.specs.locator-"
                                + System.getProperty("karafVersion", KARAF_VERSION)
                                + ".jar"),
                vmOption("--patch-module"),
                vmOption(
                        "java.xml=lib/endorsed/org.apache.karaf.specs.java.xml-"
                                + System.getProperty("karafVersion", KARAF_VERSION)
                                + ".jar"),
                vmOption("--add-opens"),
                vmOption("java.base/java.security=ALL-UNNAMED"),
                vmOption("--add-opens"),
                vmOption("java.base/java.net=ALL-UNNAMED"),
                vmOption("--add-opens"),
                vmOption("java.base/java.lang=ALL-UNNAMED"),
                vmOption("--add-opens"),
                vmOption("java.base/java.util=ALL-UNNAMED"),
                vmOption("--add-opens"),
                vmOption("java.base/jdk.internal.reflect=ALL-UNNAMED"),
                vmOption("--add-opens"),
                vmOption("java.naming/javax.naming.spi=ALL-UNNAMED"),
                vmOption("--add-opens"),
                vmOption("java.rmi/sun.rmi.transport.tcp=ALL-UNNAMED"),
                vmOption("--add-exports=java.base/sun.net.www.protocol.http=ALL-UNNAMED"),
                vmOption("--add-exports=java.base/sun.net.www.protocol.https=ALL-UNNAMED"),
                vmOption("--add-exports=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"),
                vmOption("--add-exports=jdk.naming.rmi/com.sun.jndi.url.rmi=ALL-UNNAMED"),
                vmOption("-classpath"),
                vmOption("lib/jdk9plus/*" + File.pathSeparator + "lib/boot/*"),
                vmOption("-Xmx2048M"),
                // avoid integration tests stealing focus on OS X
                vmOption("-Djava.awt.headless=true"),
                vmOption("-Dfile.encoding=UTF8"));
    }

    public static Option[] karafConfig(Class clazz) {
        return combine(configureVmOptions(),
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
                provision(new ProvisionOption[]{
                        mavenBundle()
                                .groupId("org.apache.servicemix.bundles")
                                .artifactId("org.apache.servicemix.bundles.hamcrest")
                                .versionAsInProject().start()
                }));
    }

    public static MavenArtifactUrlReference filestoreArtifact() {
        return maven().groupId("hu.blackbelt.osgi.filestore").artifactId("features").versionAsInProject().classifier("features").type("xml");
    }

    public static MavenArtifactUrlReference httpClientArtifact() {
        return maven().groupId("hu.blackbelt.karaf.features").artifactId("apache-httpclient-features").version(FEATURE_REPO_HTTP_CLIENT_VERSION).classifier("features").type("xml");
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
