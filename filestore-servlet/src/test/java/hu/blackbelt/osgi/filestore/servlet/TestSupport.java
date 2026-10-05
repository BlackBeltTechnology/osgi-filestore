package hu.blackbelt.osgi.filestore.servlet;

/*-
 * #%L
 * Filestore servlet (file upload)
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared test helpers: OSGi config annotation stubs, log capture and field injection.
 */
public final class TestSupport {

    private TestSupport() {
    }

    /**
     * Creates an instance of an OSGi component configuration annotation. Values not given in <code>values</code> fall
     * back to the annotation's own default, so tests exercise the defaults that are shipped.
     */
    @SuppressWarnings("unchecked")
    public static <T> T config(Class<T> configType, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(configType.getClassLoader(), new Class<?>[] {configType},
                (proxy, method, args) -> {
                    if ("annotationType".equals(method.getName())) {
                        return configType;
                    }
                    if ("toString".equals(method.getName())) {
                        return configType.getName() + values;
                    }
                    if (values.containsKey(method.getName())) {
                        return values.get(method.getName());
                    }
                    final Object defaultValue = method.getDefaultValue();
                    if (defaultValue == null && method.getReturnType().isPrimitive()) {
                        throw new IllegalStateException("No value for " + method.getName());
                    }
                    return defaultValue;
                });
    }

    public static void inject(Object target, String fieldName, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ex) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    public static void activate(Object component, Object config) throws Exception {
        for (final Method method : component.getClass().getDeclaredMethods()) {
            if ("activate".equals(method.getName()) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                method.invoke(component, config);
                return;
            }
        }
        throw new NoSuchMethodException("activate");
    }

    /**
     * Captures log events of the root logger for the duration of a test.
     */
    public static final class LogCapture implements AutoCloseable {

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final ch.qos.logback.classic.Logger root;

        public LogCapture() {
            root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            appender.setContext(root.getLoggerContext());
            appender.start();
            root.addAppender(appender);
        }

        public List<String> warnings() {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList());
        }

        @Override
        public void close() {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
