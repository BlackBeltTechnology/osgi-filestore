package hu.blackbelt.osgi.filestore.rdbms.fixture;

import org.jetbrains.annotations.NotNull;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.wait.LogMessageWaitStrategy;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static java.lang.String.format;
import static java.time.temporal.ChronoUnit.SECONDS;

/**
 * @author richardnorth
 */
public class YugabytedbSQLContainer<SELF extends org.testcontainers.containers.PostgreSQLContainer<SELF>> extends JdbcDatabaseContainer<SELF> {
    public static final String IMAGE = "yugabytedb/yugabyte";
    public static final String DEFAULT_TAG = "2.1.8.2-b1";

    public static final Integer YUGABYTE_PORT = 5433;

    static final String DEFAULT_USER = "yugabyte";

    static final String DEFAULT_PASSWORD = "yugabyte";

    private String databaseName = "yugabyte";
    private String username = "yugabyte";
    private String password = "yugabyte";

    //private static final String FSYNC_OFF_OPTION = "fsync=off";

    private static final String QUERY_PARAM_SEPARATOR = "&";

    public YugabytedbSQLContainer() {
        this(IMAGE + ":" + DEFAULT_TAG);
    }

    public YugabytedbSQLContainer(final String dockerImageName) {
        super(dockerImageName);
        this.waitStrategy = new LogMessageWaitStrategy()
                .withRegEx(".*yugabyted started successfully.*")
                .withTimes(1)
                .withStartupTimeout(Duration.of(60, SECONDS));
        //this.setCommand("postgres", "-c", FSYNC_OFF_OPTION);
        this.setCommand("/bin/bash", "-c", "bin/yugabyted start --ui=false && tail -f /dev/null");
        addExposedPort(YUGABYTE_PORT);
    }

    @NotNull
    @Override
    protected Set<Integer> getLivenessCheckPorts() {
        return new HashSet<>(getMappedPort(YUGABYTE_PORT));
    }

    @Override
    protected void configure() {
        // Disable Postgres driver use of java.util.logging to reduce noise at startup time
        withUrlParam("loggerLevel", "OFF");
        addEnv("POSTGRES_DB", databaseName);
        addEnv("POSTGRES_USER", username);
        addEnv("POSTGRES_PASSWORD", password);
    }

    @Override
    public String getDriverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    public String getJdbcUrl() {
        String additionalUrlParams = constructUrlParameters("?", "&");
        return "jdbc:postgresql://" + getContainerIpAddress() + ":" + getMappedPort(YUGABYTE_PORT)
                + "/" + databaseName + additionalUrlParams;
    }

    @Override
    public String getDatabaseName() {
        return databaseName;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getTestQueryString() {
        return "SELECT 1";
    }

    @Override
    public SELF withDatabaseName(final String databaseName) {
        this.databaseName = databaseName;
        return self();
    }

    @Override
    public SELF withUsername(final String username) {
        this.username = username;
        return self();
    }

    @Override
    public SELF withPassword(final String password) {
        this.password = password;
        return self();
    }

    @Override
    protected void waitUntilContainerStarted() {
        getWaitStrategy().waitUntilReady(this);
    }
}
