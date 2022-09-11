package hu.blackbelt.osgi.filestore.itest.utils;

/*-
 * #%L
 * Integeration test for JUDO framework RDBMS filestore
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

import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.TimeoutException;
import org.ops4j.pax.exam.karaf.options.LogLevelOption;
import org.ops4j.pax.exam.options.MavenArtifactUrlReference;
import org.ops4j.pax.exam.options.RawUrlReference;
import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;
import java.util.stream.Collectors;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.ops4j.pax.exam.CoreOptions.*;
import static org.ops4j.pax.exam.OptionUtils.combine;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.newConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.*;

public class KarafFeatureProvider {
    public static final String KARAF_GROUPID = "org.apache.karaf";
    public static final String APACHE_KARAF = "apache-karaf";
    public static final String ZIP = "zip";
    public static final String SERVICEMIX_BUNDLES_GROUPID = "org.apache.servicemix.bundles";
    public static final String HAMCREST = "org.apache.servicemix.bundles.hamcrest";

    public static final Integer SERVICE_TIMEOUT = 30000;
    public static final String KARAF_VERSION = "4.3.3";

    public static MavenArtifactUrlReference  karafUrl() {
        return maven()
                .groupId(KARAF_GROUPID)
                .artifactId(APACHE_KARAF)
                .versionAsInProject()
                .type(ZIP);
    }

    public static int getFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            if (serverSocket.getLocalPort() < 0) {
                throw new RuntimeException("Could not allocate port");
            }
            System.out.println("Local port: " + serverSocket.getLocalPort());
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not allocate port");
        }
    }

    public static int getKarafPort() {
        String karafPort =  System.getProperty("karafPort");
        if (karafPort == null) {
            return getFreePort();
        }
        return Integer.parseInt(karafPort);
    }

    public static Option[] karafConfig(Class<?> clazz) throws MalformedURLException {
        String startPort = Integer.toString(getKarafPort());
        return combine(configureVmOptions(), // KarafDistributionOption.debugConfiguration("5005", true),
                karafDistributionConfiguration()
                        .frameworkUrl(karafUrl())
                        .unpackDirectory(new File("target", "exam"))
                        .useDeployFolder(false),
                keepRuntimeFolder(),
                cleanCaches(true),
                logLevel(LogLevelOption.LogLevel.INFO),
                // Debug
                when(Boolean.getBoolean( "isDebugEnabled" ) ).useOptions(
                        vmOption("-DkarafPort=" + startPort),
                        vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
                ),
                //systemTimeout(30000),
                //debugConfiguration("5005", true),
                //vmOption("-Dfile.encoding=UTF-8"),
                //systemProperty("pax.exam.service.timeout").value("30000"),
                replaceConfigurationFile("etc/org.ops4j.pax.logging.cfg",
                        getConfigFile(clazz, "/etc/org.ops4j.pax.logging.cfg")),

                when(Boolean.getBoolean( "useCustomSettings")).useOptions(
                        replaceConfigurationFile("etc/org.ops4j.pax.url.mvn.cfg",
                                getConfigFile(clazz,"/etc/org.ops4j.pax.url.mvn.cfg"))
                ),

                configureConsole().ignoreLocalConsole(),

                features(new RawUrlReference(new File("target/test-classes/test-features.xml").toURI().toURL().toString()), "test"),

                newConfiguration("hu.blackbelt.jaxrs.providers.JacksonProvider")
                        .put("JacksonProvider.SerializationFeature.INDENT_OUTPUT", "true").asOption(),

                editConfigurationFilePut("etc/org.ops4j.pax.web.cfg",
                        "org.osgi.service.http.port", startPort),

                provision(
                        mavenBundle()
                                .groupId(SERVICEMIX_BUNDLES_GROUPID)
                                .artifactId(HAMCREST)
                                .versionAsInProject().start()
                ));
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

    public static File getConfigFile(Class<?> clazz, String path) {
        URL res = clazz.getResource(path);
        if (res == null) {
            throw new RuntimeException("Config resource " + path + " not found");
        }
        return new File(res.getFile());
    }

    /**
     * Explodes the dictionary into a ,-delimited list of key=value pairs
     */
    public static String explode(Dictionary<String, String> dictionary) {
        Enumeration<String> keys = dictionary.keys();
        StringBuilder result = new StringBuilder();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            result.append(String.format("%s=%s", key, dictionary.get(key)));
            if (keys.hasMoreElements()) {
                result.append(", ");
            }
        }
        return result.toString();
    }

    /*
     * Provides an iterable collection of references, even if the original array
     * is null
     */
    @SuppressWarnings("rawtypes")
    public static Collection<ServiceReference> asCollection(ServiceReference[] references) {
        return references != null ? Arrays.asList(references) : Collections.emptyList();
    }


    public static  <T> T getOsgiService(BundleContext bundleContext, Class<T> type, long timeout) {
        return getOsgiService(bundleContext, type, null, timeout);
    }

    public static  <T> T getOsgiService(BundleContext bundleContext, Class<T> type) {
        return getOsgiService(bundleContext, type, null, SERVICE_TIMEOUT);
    }

    @SuppressWarnings("unchecked")
    public static  <T> T getOsgiService(BundleContext bundleContext, Class<T> type, String filter, long timeout) {
        ServiceTracker<T, T> tracker;
        try {
            String flt;
            if (filter != null) {
                if (filter.startsWith("(")) {
                    flt = "(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")" + filter + ")";
                } else {
                    flt = "(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")(" + filter + "))";
                }
            } else {
                flt = "(" + Constants.OBJECTCLASS + "=" + type.getName() + ")";
            }
            Filter osgiFilter = FrameworkUtil.createFilter(flt);
            tracker = new ServiceTracker(bundleContext, osgiFilter, null);
            tracker.open(true);
            // Note that the tracker is not closed to keep the reference
            // This is buggy, as the service reference may change i think
            Object svc = type.cast(tracker.waitForService(timeout));
            if (svc == null) {
                Dictionary<String, String> dic = bundleContext.getBundle().getHeaders();
                System.err.println("Test bundle headers: " + explode(dic));

                for (ServiceReference<T> ref : asCollection(bundleContext.getAllServiceReferences(null, null))) {
                    System.err.println("ServiceReference: " + ref);
                }

                for (ServiceReference<T> ref : asCollection(bundleContext.getAllServiceReferences(null, flt))) {
                    System.err.println("Filtered ServiceReference: " + ref);
                }

                throw new RuntimeException("Gave up waiting for service " + flt);
            }
            return type.cast(svc);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException("Invalid filter", e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    public static File testTargetDir(Class<?> clazz){
        String relPath = clazz.getProtectionDomain().getCodeSource().getLocation().getFile();
        File targetDir = new File(relPath);
        if(!targetDir.exists()) {
            targetDir.mkdir();
        }
        return targetDir;
    }


    public static void assertBundleStarted(BundleContext bundleContext, String name) {
        Bundle bundle = findBundleByName(bundleContext, name);
        assertNotNull("Bundle " + name + " should be installed", bundle);
        assertEquals("Bundle " + name + " should be started", Bundle.ACTIVE, bundle.getState());
    }

    public static  Bundle findBundleByName(BundleContext bundleContext, String symbolicName) {
        for (Bundle bundle : bundleContext.getBundles()) {
            if (bundle.getSymbolicName().equals(symbolicName)) {
                return bundle;
            }
        }
        return null;
    }

    public static  void waitWebPage(String urlSt) throws InterruptedException, TimeoutException {
        System.out.println("Waiting for url " + urlSt);
        HttpURLConnection con = null;
        long startTime = System.currentTimeMillis();
        while (true) {
            try {
                URL url = new URL(urlSt);
                con = (HttpURLConnection)url.openConnection();
                int status = con.getResponseCode();
                if (status == 200) {
                    final BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
                    String responseBody = br.lines().collect(Collectors.joining());
                    System.out.println("WADL returned:\n" + responseBody);
                    return;
                }
            } catch (ConnectException e) {
                // Ignore connection refused
            } catch (MalformedURLException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
            sleepOrTimeout(startTime, 20, "Timeout waiting for web page " + urlSt);
        }
    }

    /**
     * Sleeps for a short interval, throwing an exception if timeout has been reached. Used to facilitate a
     * retry interval with timeout when used in a loop.
     *
     * @param startTime the start time of the entire operation in milliseconds
     * @param timeout the timeout duration for the entire operation in seconds
     * @param message the error message to use when timeout occurs
     * @throws InterruptedException if interrupted while sleeping
     */
    public static void sleepOrTimeout(long startTime, long timeout, String message)
            throws InterruptedException, TimeoutException {
        timeout *= 1000; // seconds to millis
        long elapsed = System.currentTimeMillis() - startTime;
        long remaining = timeout - elapsed;
        if (remaining <= 0) {
            throw new TimeoutException(message);
        }
        long interval = Math.min(remaining, 1000);
        Thread.sleep(interval);
    }
}
