package org.example.rpc.config;

import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import io.grpc.BindableService;
import org.example.rpc.client.GrpcClientBeanPostProcessor;
import org.example.rpc.context.ClientContext;
import org.example.rpc.context.ClientContextServerInterceptor;
import org.example.rpc.schema.GrpcJsonSchemaGenerator;
import org.example.rpc.schema.SchemaEndpoint;
import org.example.rpc.server.AppIdReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.net.InetAddress;
import java.util.Map;
import java.util.Properties;

@AutoConfiguration
@EnableConfigurationProperties(RpcProperties.class)
public class RpcAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RpcAutoConfiguration.class);

    @Bean
    public NamingService namingService(RpcProperties properties) throws Exception {
        Properties props = new Properties();
        props.setProperty("serverAddr", properties.getNacos().getServerAddr());
        String namespace = properties.getNacos().getNamespace();
        if (namespace != null && !namespace.isEmpty()) {
            props.setProperty("namespace", namespace);
        }
        return NamingFactory.createNamingService(props);
    }

    @Bean
    public ClientContext clientContext(RpcProperties properties) throws Exception {
        String hostname = InetAddress.getLocalHost().getHostName();
        String ip = InetAddress.getLocalHost().getHostAddress();
        String idc = properties.getClient().getIdc();
        String groupName = properties.getClient().getGroupName();
        log.info("ClientContext created: ip={}, hostname={}, idc={}, groupName={}",
                ip, hostname, idc, groupName);
        return new ClientContext(ip, hostname, idc, groupName);
    }

    @Bean
    public GrpcClientBeanPostProcessor grpcClientBeanPostProcessor(
            NamingService namingService, ClientContext clientContext) {
        return new GrpcClientBeanPostProcessor(namingService, clientContext);
    }

    @Bean
    public ClientContextServerInterceptor clientContextServerInterceptor() {
        return new ClientContextServerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcJsonSchemaGenerator grpcJsonSchemaGenerator(RpcProperties properties) {
        return new GrpcJsonSchemaGenerator(properties);
    }

    @Bean
    public SchemaEndpoint schemaEndpoint(GrpcJsonSchemaGenerator generator) {
        return new SchemaEndpoint(generator);
    }

    @Bean(destroyMethod = "stop")
    public Server armeriaServer(ApplicationContext ctx, RpcProperties properties,
                                 NamingService namingService,
                                 SchemaEndpoint schemaEndpoint,
                                 ClientContextServerInterceptor serverInterceptor) throws Exception {

        Map<String, Object> services = ctx.getBeansWithAnnotation(
                org.example.rpc.annotation.GrpcService.class);

        GrpcServiceBuilder grpcServiceBuilder = com.linecorp.armeria.server.grpc.GrpcService.builder()
                .enableUnframedRequests(true)
                .intercept(serverInterceptor);

        // 服务端 appid 从 proto 文件的 option (appid) 读取，与客户端逻辑一致
        String appId = null;
        for (Object bean : services.values()) {
            if (bean instanceof BindableService bindable) {
                grpcServiceBuilder.addService(bindable);
                if (appId == null) {
                    appId = AppIdReader.getAppId(
                            bindable.bindService().getServiceDescriptor());
                }
            }
        }

        if (services.isEmpty()) {
            throw new IllegalStateException(
                    "No @GrpcService beans found. Mark your BindableService implementations with @GrpcService.");
        }
        if (appId == null) {
            throw new IllegalStateException(
                    "No BindableService found among @GrpcService beans, cannot determine appId.");
        }

        Server server = Server.builder()
                .http(properties.getPort())
                .service(grpcServiceBuilder.build())
                .service("/rpc/schema", schemaEndpoint)
                .build();

        server.start().join();
        log.info("Armeria server started on port {} with appId={}", properties.getPort(), appId);

        String host = InetAddress.getLocalHost().getHostAddress();
        namingService.registerInstance(appId, host, properties.getPort());
        log.info("Registered service {} -> {}:{} to Nacos", appId, host, properties.getPort());

        return server;
    }
}
