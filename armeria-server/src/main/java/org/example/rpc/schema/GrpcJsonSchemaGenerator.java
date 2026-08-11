package org.example.rpc.schema;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import io.grpc.BindableService;
import org.example.rpc.annotation.GrpcService;
import org.example.rpc.config.RpcProperties;
import org.example.rpc.server.AppIdReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GrpcJsonSchemaGenerator {

    private static final Logger log = LoggerFactory.getLogger(GrpcJsonSchemaGenerator.class);

    private final RpcProperties properties;
    private final Map<String, Object> schemas = new LinkedHashMap<>();

    public GrpcJsonSchemaGenerator(RpcProperties properties) {
        this.properties = properties;
    }

    @EventListener
    public void onApplicationReady(ContextRefreshedEvent event) {
        if (!properties.isSchemaEnabled()) return;

        ApplicationContext ctx = event.getApplicationContext();
        Map<String, Object> beans = ctx.getBeansWithAnnotation(GrpcService.class);

        for (Object bean : beans.values()) {
            if (!(bean instanceof BindableService bindable)) continue;

            io.grpc.ServiceDescriptor sd = bindable.bindService().getServiceDescriptor();
            // 服务端启动时已校验 app_id 存在，这里直接读取，无需兜底
            String appId = AppIdReader.getAppId(sd);

            for (io.grpc.MethodDescriptor<?, ?> method : sd.getMethods()) {
                String key = appId + "/api/" + method.getBareMethodName();

                Object requestTemplate;
                try {
                    requestTemplate = buildTemplate(getDefaultInstance(method));
                } catch (Exception e) {
                    log.warn("Failed to build template for {}", key, e);
                    requestTemplate = Map.of("_error", "Failed to introspect: " + e.getMessage());
                }

                schemas.put(key, requestTemplate);
            }
        }

        log.info("Generated {} gRPC request schemas", schemas.size());
    }

    /**
     * Resolves the request message's default instance from the method's
     * marshaller. grpc-java 1.28+ exposes {@code getMessagePrototype()};
     * older versions expose {@code getDefaultInstance()}. The marshaller class
     * is package-private, so the accessor must be made accessible explicitly.
     */
    private Message getDefaultInstance(io.grpc.MethodDescriptor<?, ?> method) throws Exception {
        Object marshaller = method.getRequestMarshaller();
        Method accessor;
        try {
            accessor = marshaller.getClass().getMethod("getMessagePrototype");
        } catch (NoSuchMethodException e) {
            accessor = marshaller.getClass().getMethod("getDefaultInstance");
        }
        accessor.setAccessible(true);
        return (Message) accessor.invoke(marshaller);
    }

    private Object buildTemplate(Message message) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Descriptors.FieldDescriptor field : message.getDescriptorForType().getFields()) {
            result.put(field.getName(), defaultValue(field));
        }
        return result;
    }

    private Object defaultValue(Descriptors.FieldDescriptor field) {
        if (field.isRepeated()) return List.of();
        return switch (field.getJavaType()) {
            case INT, LONG -> 0;
            case FLOAT, DOUBLE -> 0.0;
            case BOOLEAN -> false;
            case STRING -> "";
            case BYTE_STRING -> "";
            case ENUM -> field.getEnumType().getValues().get(0).getName();
            case MESSAGE -> buildTemplate(
                    DynamicMessage.newBuilder(field.getMessageType()).buildPartial());
        };
    }

    public Map<String, Object> getSchemas() {
        return schemas;
    }
}
