package hu.blackbelt.osgi.filestore.rdbms.fixture;

import liquibase.resource.ResourceAccessor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class StreamResourceAccessor implements ResourceAccessor {

    @NonNull
    private final Map<String, InputStream> streams;

    public Set<InputStream> getResourcesAsStream(final String path) {
        final Set<InputStream> result = streams.entrySet().stream()
                .filter(s -> s.getKey().startsWith(path))
                .map(s -> s.getValue())
                .collect(Collectors.toSet());
        return result;
    }

    public Set<String> list(final String relativeTo, final String path, final boolean includeFiles, final boolean includeDirectories, final boolean recursive) {
        final Set<String> result = streams.entrySet().stream()
                .filter(s -> recursive ? s.getKey().startsWith(path) : s.getKey().equals(path))
                .map(s -> s.getKey())
                .collect(Collectors.toSet());
        return result;
    }

    public ClassLoader toClassLoader() {
        return null;
    }
}
