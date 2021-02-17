package hu.blackbelt.osgi.filestore.rdbms;

import com.google.common.collect.ImmutableMap;
import hu.blackbelt.osgi.liquibase.LiquibaseExecutor;
import liquibase.exception.LiquibaseException;
import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Slf4j
public class RdbmsFileStoreLiquibaseExecutor {

    @ObjectClassDefinition()
    public @interface Config {

        @AttributeDefinition(name="Table", description = "Table to store files")
        String table() default "FILESTORE";
    }

    @Reference
    DataSource dataSource;

    @Reference
    LiquibaseExecutor liquibaseExecutor;

    @Activate
    public void activate(BundleContext bundleContext, Config config) {
        try (Connection connection = dataSource.getConnection()) {
            liquibaseExecutor.executeLiquibaseScript(connection, "liquibase/changelog.xml", bundleContext.getBundle(),
                    ImmutableMap.of("table-name", config.table())
            );
        } catch (SQLException | LiquibaseException e) {
            log.error("Could not execute liquibase script", e);
        }
    }
}
