package com.armeria.grpc.common.env;

/**
 * Resolves the effective application identity. {@code META-INF/app.config}
 * (exposed as {@code app.id}) is mandatory for every application; the proto
 * service option {@code app_id} is optional (pure clients have none) but, when
 * present, must be equal. A missing {@code app.id} or a mismatch fails fast so
 * a mis-configured deployment is caught at startup.
 */
public final class AppIdResolver {

    private AppIdResolver() {
    }

    public static String resolve(String configAppId, String protoAppId) {
        if (configAppId == null || configAppId.isBlank()) {
            throw new IllegalStateException(
                    "app.id is not configured: every application must define '"
                            + AppIdEnvironmentPostProcessor.APPID_KEY
                            + "=...' in META-INF/app.config");
        }
        if (protoAppId != null && !configAppId.equals(protoAppId)) {
            throw new IllegalStateException(
                    "app.id (" + configAppId + ") from META-INF/app.config does not match "
                            + "proto app_id (" + protoAppId + ") of the deployed service");
        }
        return configAppId;
    }
}
