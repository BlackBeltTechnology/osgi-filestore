package hu.blackbelt.osgi.filestore.rdbms.fixture;


import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosNonXADataSourceBean;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.core.HsqlDatabase;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.HsqlConnection;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.ttddyy.dsproxy.listener.logging.DefaultQueryLogEntryCreator;
import net.ttddyy.dsproxy.listener.logging.SLF4JQueryLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import javax.transaction.*;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
public class RdbmsDatasourceFixture {

    public static final String CONTAINER_NONE = "none";
    public static final String CONTAINER_POSTGRESQL = "postgresql";
    public static final String CONTAINER_YUGABYTEDB = "yugabytedb";
    public static final String HSQLDB = "hsqldb";
    public static final String POSTGRESQL = "postgresql";

    @Getter
    protected String dialect = System.getProperty("dialect", HSQLDB);

    @Getter
    protected String container = System.getProperty("container", CONTAINER_NONE);

    @Getter
    protected DataSource dataSource;

    @Getter
    protected Database liquibaseDb;

    @Getter
    TransactionManager transactionManager;

    public JdbcDatabaseContainer sqlContainer;

    public void setupDatasource() {
        if (dialect.equals(POSTGRESQL)) {
            if (container.equals(CONTAINER_NONE) || container.equals(CONTAINER_POSTGRESQL)) {
                sqlContainer =
                        (PostgreSQLContainer) new PostgreSQLContainer().withStartupTimeout(Duration.ofSeconds(600));
            } else if (container.equals(CONTAINER_YUGABYTEDB)) {
                sqlContainer =
                        (YugabytedbSQLContainer) new YugabytedbSQLContainer().withStartupTimeout(Duration.ofSeconds(600));
            }
        }
    }

    public void teardownDatasource() throws Exception {
        if (sqlContainer != null && sqlContainer.isRunning()) {
            sqlContainer.stop();
        }
    }


    @SneakyThrows
    public void prepareDatasources() {
        transactionManager = new UserTransactionManager();

        System.setProperty("com.atomikos.icatch.registered", "true");

        AtomikosNonXADataSourceBean ds = new AtomikosNonXADataSourceBean();
        ds.setPoolSize(10);
        ds.setLocalTransactionMode(true);
        ds.setUniqueResourceName("db");

        if (dialect.equals(HSQLDB)) {
            ds.setUniqueResourceName(HSQLDB);
            ds.setDriverClassName("org.hsqldb.jdbcDriver");
            ds.setUrl("jdbc:hsqldb:mem:memdb");
            ds.setUser("sa");
            ds.setPassword("saPassword");
            liquibaseDb = new HsqlDatabase();
        } else if (dialect.equals(POSTGRESQL)) {
            sqlContainer.start();
            ds.setDriverClassName(sqlContainer.getDriverClassName());
            ds.setUrl(sqlContainer.getJdbcUrl());
            ds.setUser(sqlContainer.getUsername());
            ds.setPassword(sqlContainer.getPassword());
            liquibaseDb = new PostgresDatabase();
        } else {
            throw new IllegalStateException("Unsupported dialect: " + dialect);
        }

        SLF4JQueryLoggingListener loggingListener = new SLF4JQueryLoggingListener();
        loggingListener.setQueryLogEntryCreator(new DefaultQueryLogEntryCreator());

        dataSource = ProxyDataSourceBuilder
                .create(ds)
                .name("DATA_SOURCE_PROXY")
                .listener(loggingListener)
                .build();

        // Execute dialect based datatsource preprations
//        if (dialect.equals("postgresql")) {
//            executeInitiLiquibase(ModelDataSourcePostgresqlInitTracker.class.getClassLoader(), "liquibase/postgresql-init-changelog.xml", ds);
//        }

    }

    @SneakyThrows
    public void setLiquibaseDbDialect(Connection connection) {
        if (HSQLDB.equals(dialect)) {
            liquibaseDb.setConnection(new HsqlConnection(connection));
        } else {
            liquibaseDb.setConnection(new JdbcConnection(connection));
        }
        liquibaseDb.setAutoCommit(false);
    }

    public void executeInitiLiquibase(ClassLoader classLoader, String name, DataSource dataSource, Map<String, Object> parameters) {
        try {
            setLiquibaseDbDialect(dataSource.getConnection());
            final Liquibase liquibase = new Liquibase(name,
                    new ClassLoaderResourceAccessor(classLoader), liquibaseDb);
            if (parameters != null) {
                parameters.entrySet().stream().forEach(e -> liquibase.setChangeLogParameter(e.getKey(), e.getValue()));
            }
            liquibase.update("init," + "1.0.0");
            liquibaseDb.close();
        } catch (Exception e) {
            log.error("Error init liquibase", e);
            throw new RuntimeException(e);
        }
    }

    public <T extends Throwable> T assertThrowsInTransaction(final Class<T> expectedType, final Executable executable) {
            return Assertions.assertThrows(expectedType, () -> {
                getTransactionManager().begin();
                try {
                    executable.execute();
                } catch (Exception e) {
                    if (getTransactionManager().getStatus() == Status.STATUS_ACTIVE) {
                        getTransactionManager().rollback();
                    }
                } finally {
                    if (getTransactionManager().getStatus() == Status.STATUS_ACTIVE) {
                        getTransactionManager().commit();
                    }
                }
            });
    }

    @SneakyThrows
    public <R> R runInTransaction(Supplier<R> executable) {
        getTransactionManager().begin();
        try {
            return executable.get();
        } catch (Exception e) {
            if (getTransactionManager().getStatus() == Status.STATUS_ACTIVE) {
                getTransactionManager().rollback();
            }
        } finally {
            if (getTransactionManager().getStatus() == Status.STATUS_ACTIVE) {
                getTransactionManager().commit();
            }
        }
        return null;
    }

}
