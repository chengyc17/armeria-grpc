package org.example.rpc.server;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;

public final class AppIdReader {

    /** extend google.protobuf.ServiceOptions 时用的 field number */
    private static final int APPID_FIELD_NUMBER = 1001;

    private AppIdReader() {}

    /**
     * 从 proto ServiceOptions 中读取 (app_id) option。
     * 对应 proto:
     * <pre>
     * extend google.protobuf.ServiceOptions {
     *   string app_id = 1001;
     * }
     * service UserService {
     *   option (app_id) = "your-app-id";
     *   ...
     * }
     * </pre>
     */
    public static String getAppId(io.grpc.ServiceDescriptor descriptor) {
        Object schema = descriptor.getSchemaDescriptor();
        if (!(schema instanceof ProtoServiceDescriptorSupplier supplier)) {
            throw new IllegalStateException(
                    "Service " + descriptor.getName() + " does not provide a protobuf service descriptor: "
                            + schema);
        }
        Descriptors.ServiceDescriptor serviceSd = supplier.getServiceDescriptor();

        DescriptorProtos.ServiceOptions options = serviceSd.getOptions();
        // protoc stores the value as a registered extension of ServiceOptions.
        Descriptors.FieldDescriptor appIdExtension = serviceSd.getFile().getExtensions().stream()
                .filter(fd -> fd.getNumber() == APPID_FIELD_NUMBER)
                .findFirst()
                .orElse(null);
        if (appIdExtension != null && options.hasField(appIdExtension)) {
            return (String) options.getField(appIdExtension);
        }

        throw new IllegalStateException(
                "app_id (field " + APPID_FIELD_NUMBER + ") not found in service options of "
                        + serviceSd.getName()
                        + ". Add 'option (app_id) = \"your-app-id\";' inside the service definition.");
    }
}
