package org.example.rpc.config;

import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import io.grpc.BindableService;
import io.netty.util.internal.StringUtil;
import org.example.config.AppConfigService;
import org.example.rpc.client.GrpcClientBeanPostProcessor;
import org.example.rpc.context.ClientContext;
import org.example.rpc.context.ClientContextServerInterceptor;
import org.example.rpc.governance.GovernanceConfigManager;
import org.example.rpc.governance.GovernanceServerInterceptor;
import org.example.rpc.schema.GrpcJsonSchemaGenerator;
import org.example.rpc.schema.SchemaEndpoint;
import org.example.rpc.server.AppIdReader;
import org.example.rpc.server.GrpcServices;
import org.example.rpc.server.NacosInstanceRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.Optional;
import java.util.Properties;

@AutoConfiguration
@EnableConfigurationProperties(RpcProperties.class)
public class RpcAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RpcAutoConfiguration.class);

    @Bean(destroyMethod = "shutDown")
    public NamingService namingService(RpcProperties properties, Environment environment) throws Exception {
        Properties props = new Properties();
        String addr = Optional.ofNullable(properties).map(x -> x.getNacos()).map(x -> x.getServerAddr()).orElse(null);
        if (StringUtil.isNullOrEmpty(addr)) {
            throw new IllegalStateException("nacos register address can not be empty");
        }
        props.setProperty("serverAddr", addr);
        props.setProperty("namespace", environment.getProperty("env"));
        return NamingFactory.createNamingService(props);
    }

    @Bean(destroyMethod = "deregister")
    public NacosInstanceRegistrar nacosInstanceRegistrar(NamingService namingService) {
        return new NacosInstanceRegistrar(namingService);
    }

    @Bean
    public ClientContext clientContext(RpcProperties properties, ApplicationContext context,
                                       Environment environment) throws Exception {
        String hostname = InetAddress.getLocalHost().getHostName();
        String ip = InetAddress.getLocalHost().getHostAddress();
        String idc = properties.getClient().getIdc();
        String groupName = properties.getClient().getGroupName();
        // 自身 appId：优先取 proto 的 option (app_id)；纯客户端进程没有 @GrpcService，
        String appId = GrpcServices.findAppId(context);
        log.info("ClientContext created: appId={}, ip={}, hostname={}, idc={}, groupName={}",
                appId, ip, hostname, idc, groupName);
        return new ClientContext(appId, ip, hostname, idc, groupName);
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

    @Bean
    public GovernanceConfigManager governanceConfigManager(ObjectProvider<AppConfigService> configServiceProvider,
                                                           ApplicationContext context) {
        return new GovernanceConfigManager(configServiceProvider, GrpcServices.resolveAppId(context));
    }

    @Bean
    public GovernanceServerInterceptor governanceServerInterceptor(GovernanceConfigManager manager) {
        return new GovernanceServerInterceptor(manager);
    }

    @Bean(destroyMethod = "stop")
    public Server armeriaServer(ApplicationContext ctx, RpcProperties properties,
                                NacosInstanceRegistrar registrar,
                                SchemaEndpoint schemaEndpoint,
                                ClientContextServerInterceptor serverInterceptor,
                                ObjectProvider<GovernanceServerInterceptor> governanceInterceptorProvider) throws Exception {

        GrpcServiceBuilder grpcServiceBuilder = GrpcService.builder()
                .enableUnframedRequests(true)
                .intercept(serverInterceptor);
        GrpcServiceBuilder shortPathBuilder = GrpcService.builder()
                .enableUnframedRequests(true)
                .intercept(serverInterceptor);
        GovernanceServerInterceptor governanceInterceptor = governanceInterceptorProvider.getIfAvailable();
        if (governanceInterceptor != null) {
            grpcServiceBuilder.intercept(governanceInterceptor);
            shortPathBuilder.intercept(governanceInterceptor);
        }

        // 一个服务实例只允许一个 @GrpcService 实现，对应注册中心里的唯一 appId；
        // appId 从 proto 文件的 option (app_id) 读取，与客户端逻辑一致
        BindableService grpcService = GrpcServices.requireSingle(ctx);
        String appId = AppIdReader.getAppId(grpcService.bindService().getServiceDescriptor());

        // 保持默认 gRPC 路径 /{包名.服务名}/{方法名}，由下方 serviceUnder 挂到 /{appId}/api 前缀下
        grpcServiceBuilder.addService(grpcService);
        // also serve the short path /{appId}/api/{MethodName} for HTTP/JSON access
        shortPathBuilder.addService("/" + appId + "/api", grpcService);

        Server server = Server.builder()
                .http(properties.getPort())
                .serviceUnder("/" + appId + "/api", grpcServiceBuilder.build())
                .service(shortPathBuilder.build())
                .service("/rpc/schema", schemaEndpoint)
                .build();

        server.start().join();
        log.info("Armeria server started on port {} with appId={}", properties.getPort(), appId);

        String host = InetAddress.getLocalHost().getHostAddress();
        try {
            registrar.register(appId, host, properties.getPort());
        } catch (Exception e) {
            // 注册失败时停掉已启动的 Armeria server，避免端口被泄漏占用
            // （否则进程会残留监听端口，下一次启动报 Address already in use: bind）
            server.stop().join();
            throw e;
        }
        log.info("Registered service {} -> {}:{} to Nacos", appId, host, properties.getPort());

        return server;
    }
}
