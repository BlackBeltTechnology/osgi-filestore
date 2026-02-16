package hu.blackbelt.osgi.filestore.s3.fixture;

import org.junit.jupiter.api.extension.*;

public class MinioSingletonExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static MinioFixture minioFixture = new MinioFixture();
    private static boolean initialized = false;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (!initialized) {
            minioFixture.setupMinio();
            initialized = true;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        // Keep container running across test classes for performance
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType().isAssignableFrom(MinioFixture.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return minioFixture;
    }
}
