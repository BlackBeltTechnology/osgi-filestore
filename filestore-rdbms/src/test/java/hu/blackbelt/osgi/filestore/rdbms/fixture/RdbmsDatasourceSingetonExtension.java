package hu.blackbelt.osgi.filestore.rdbms.fixture;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.*;

import javax.transaction.Status;

public class RdbmsDatasourceSingetonExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static RdbmsDatasourceFixture  rdbmsDatasourceFixture =  new RdbmsDatasourceFixture();
    private static boolean initialized = false;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (!initialized) {
            rdbmsDatasourceFixture.setupDatasource();
            rdbmsDatasourceFixture.prepareDatasources();
            initialized = true;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        // rdbmsDatasourceFixture.teardownDatasource();
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

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
    }
}
