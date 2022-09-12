package hu.blackbelt.osgi.filestore.rdbms.fixture;

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

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.*;

import javax.transaction.Status;

public class RdbmsDatasourceByClassExtension implements BeforeAllCallback, AfterAllCallback, AfterEachCallback, ParameterResolver {

    private RdbmsDatasourceFixture  rdbmsDatasourceFixture =  new RdbmsDatasourceFixture();

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        rdbmsDatasourceFixture.setupDatasource();
        rdbmsDatasourceFixture.prepareDatasources();
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        rdbmsDatasourceFixture.teardownDatasource();
    }


    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType().isAssignableFrom(RdbmsDatasourceFixture.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return rdbmsDatasourceFixture;
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        if (rdbmsDatasourceFixture.getTransactionManager().getStatus() == Status.STATUS_ACTIVE) {
            rdbmsDatasourceFixture.getTransactionManager().commit();
        }
    }

}
