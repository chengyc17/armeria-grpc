package org.example.common.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 从本机 ops 目录读取 env.properties，把其中的配置注入 Spring Environment。
 * <p>
 * Windows 读取 {@code C:\ops\env.properties}，Linux/其他读取 {@code {user.home}/ops/env.properties}。
 * 环境值（key 为 {@code env}）可直接用 {@code ${env}} 引用；
 * 文件不存在、读取失败或缺少 {@code env} 时直接报错，阻止服务启动。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(OpsEnvironmentPostProcessor.class);

    public static final String PROPERTY_SOURCE_NAME = "opsEnvProperties";
    public static final String ENV_KEY = "env";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = opsEnvFile();
        Map<String, Object> props = loadOpsEnv(file);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
    }

    /**
     * 读取并校验 ops env 配置；文件缺失、读取失败或没有 env 键时抛出异常。
     */
    static Map<String, Object> loadOpsEnv(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    "Ops env properties not found at " + file
                            + ". Please create it and set 'env=<environment>'.");
        }
        Map<String, Object> props = new HashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            Properties properties = new Properties();
            properties.load(in);
            properties.forEach((key, value) -> props.put(String.valueOf(key), String.valueOf(value)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ops env properties from " + file, e);
        }
        String env = (String) props.get(ENV_KEY);
        if (env == null || env.isBlank()) {
            throw new IllegalStateException(
                    "'env' key is missing or blank in " + file + ". Please set 'env=<environment>'.");
        }
        log.info("Loaded ops env properties from {} (env={})", file, env);
        return props;
    }

    static Path opsEnvFile() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String baseDir = os.contains("win")
                ? "C:\\ops"
                : System.getProperty("user.home") + File.separator + "ops";
        return Paths.get(baseDir, "env.properties");
    }
}
