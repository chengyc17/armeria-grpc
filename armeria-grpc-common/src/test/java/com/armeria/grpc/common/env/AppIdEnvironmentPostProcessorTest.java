package com.armeria.grpc.common.env;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppIdEnvironmentPostProcessorTest {

    @Test
    void loadsAppidKeyAndNormalizesToAppId() throws Exception {
        Map<String, Object> props = load("appid=10000784\n");

        assertEquals("10000784", props.get(AppIdEnvironmentPostProcessor.APP_ID_PROPERTY));
        assertEquals("10000784", props.get(AppIdEnvironmentPostProcessor.APPID_KEY));
    }

    @Test
    void acceptsBothKeysWhenEqual() throws Exception {
        Map<String, Object> props = load("app.id=10000001\nappid=10000001\n");

        assertEquals("10000001", props.get(AppIdEnvironmentPostProcessor.APP_ID_PROPERTY));
    }

    @Test
    void conflictingKeysFailFast() {
        assertThrows(IllegalStateException.class,
                () -> load("app.id=10000001\nappid=10000002\n"));
    }

    @Test
    void missingKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> load("other=1\n"));
    }

    @Test
    void blankValueFailsFast() {
        assertThrows(IllegalStateException.class, () -> load("appid=  \n"));
    }

    private static Map<String, Object> load(String content) throws Exception {
        try (InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            return AppIdEnvironmentPostProcessor.load(in);
        }
    }
}
