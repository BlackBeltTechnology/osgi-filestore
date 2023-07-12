package hu.blackbelt.osgi.filestore.rdbms;

/*-
 * #%L
 * JUDO framework RDBMS filestore
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
                    ImmutableMap.of("table-name", config.table().toUpperCase())
            );
        } catch (SQLException | LiquibaseException e) {
            log.error("Could not execute liquibase script", e);
        }
    }
}
