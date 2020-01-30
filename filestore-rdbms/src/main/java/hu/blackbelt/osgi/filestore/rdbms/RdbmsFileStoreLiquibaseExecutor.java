package hu.blackbelt.osgi.filestore.rdbms;

import hu.blackbelt.osgi.liquibase.LiquibaseExecutor;
import liquibase.exception.LiquibaseException;
import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Slf4j
public class RdbmsFileStoreLiquibaseExecutor {

    @Reference
    DataSource dataSource;

    @Reference
    LiquibaseExecutor liquibaseExecutor;

    @Activate
    public void activate(BundleContext bundleContext) {
        try (Connection connection = dataSource.getConnection()) {
            liquibaseExecutor.executeLiquibaseScript(connection, "liquibase/changelog.xml", bundleContext.getBundle());
        } catch (SQLException | LiquibaseException e) {
            log.error("Could not execute liquibase script", e);
        }
    }
}
