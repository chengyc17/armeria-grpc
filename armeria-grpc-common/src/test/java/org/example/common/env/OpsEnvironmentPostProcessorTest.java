package org.example.common.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void failsWhenFileMissing() {
        Path missing = tempDir.resolve("env.properties");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OpsEnvironmentPostProcessor.loadOpsEnv(missing));

        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void failsWhenEnvKeyMissing() throws Exception {
        Path file = tempDir.resolve("env.properties");
        Files.writeString(file, "other=1\n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OpsEnvironmentPostProcessor.loadOpsEnv(file));

        assertTrue(e.getMessage().contains("env"));
    }

    @Test
    void loadsEnvValue() throws Exception {
        Path file = tempDir.resolve("env.properties");
        Files.writeString(file, "env=fat\nother=1\n");

        Map<String, Object> props = OpsEnvironmentPostProcessor.loadOpsEnv(file);

        assertEquals("fat", props.get("env"));
        assertEquals("1", props.get("other"));
    }
}
