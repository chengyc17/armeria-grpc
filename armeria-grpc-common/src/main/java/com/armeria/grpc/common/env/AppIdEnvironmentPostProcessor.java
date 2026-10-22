package com.armeria.grpc.common.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads the application identity from {@code META-INF/app.config} (properties
 * format, key {@code appid} or {@code app.id}) and registers it in the Spring
 * Environment as {@code app.id} (plus the original {@code appid} key).
 *
 * <p>Every application must carry this file; a missing file fails fast at
 * startup. A present-but-invalid file, including conflicting
 * {@code appid}/{@code app.id} keys, also fails fast. {@link AppIdResolver}
 * additionally checks the proto {@code app_id} (when a gRPC service exists)
 * against this value.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AppIdEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AppIdEnvironmentPostProcessor.class);

    public static final String PROPERTY_SOURCE_NAME = "appConfigProperties";
    public static final String APP_ID_PROPERTY = "app.id";
    public static final String APPID_KEY = "appid";
    public static final String RESOURCE_LOCATION = "META-INF/app.config";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassPathResource resource = new ClassPathResource(RESOURCE_LOCATION);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    RESOURCE_LOCATION + " not found on the classpath. Every application must define "
                            + "its identity there, e.g. '" + APPID_KEY + "=10000784'.");
        }
        try (InputStream in = resource.getInputStream()) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, load(in)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE_LOCATION, e);
        }
    }

    /**
     * Parses the app.config properties and normalizes the identity to the
     * {@value APP_ID_PROPERTY} key. Package-private for unit testing.
     */
    static Map<String, Object> load(InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(in);

        String appId = first(properties);
        if (appId == null) {
            throw new IllegalStateException(
                    RESOURCE_LOCATION + " exists but contains no '" + APPID_KEY
                            + "' (or '" + APP_ID_PROPERTY + "') entry");
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(APP_ID_PROPERTY, appId);
        props.put(APPID_KEY, appId);
        log.info("Loaded app identity from {} (app.id={})", RESOURCE_LOCATION, appId);
        return props;
    }

    private static String first(Properties properties) {
        String value = null;
        for (String key : new String[]{APP_ID_PROPERTY, APPID_KEY}) {
            String v = properties.getProperty(key);
            if (v == null || v.isBlank()) {
                continue;
            }
            v = v.trim();
            if (value != null && !value.equals(v)) {
                throw new IllegalStateException(
                        "Conflicting app identity in " + RESOURCE_LOCATION + ": "
                                + APP_ID_PROPERTY + "=" + value + " but " + key + "=" + v);
            }
            value = v;
        }
        return value;
    }
}
