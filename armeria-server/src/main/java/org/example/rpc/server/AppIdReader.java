package org.example.rpc.server;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.UnknownFieldSet;

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
        if (!(schema instanceof Descriptors.ServiceDescriptor serviceSd)) {
            throw new IllegalStateException(
                    "Service " + descriptor.getName() + " does not have a protobuf schema descriptor");
        }
        DescriptorProtos.ServiceOptions options = serviceSd.getOptions();
        UnknownFieldSet ufs = options.getUnknownFields();
        if (!ufs.hasField(APPID_FIELD_NUMBER)) {
            throw new IllegalStateException(
                    "app_id (field " + APPID_FIELD_NUMBER + ") not found in service options of "
                            + serviceSd.getName()
                            + ". Add 'option (app_id) = \"your-app-id\";' inside the service definition.");
        }
        return ufs.getField(APPID_FIELD_NUMBER).getLengthDelimitedList().get(0).toStringUtf8();
    }
}
