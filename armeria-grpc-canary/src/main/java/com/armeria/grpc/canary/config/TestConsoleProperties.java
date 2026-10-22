package com.armeria.grpc.canary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 测试控制台配置，对应 application.yml 中的 test-console 前缀。
 */
@ConfigurationProperties("test-console")
public class TestConsoleProperties {

    private final Nacos nacos = new Nacos();

    public Nacos getNacos() {
        return nacos;
    }

    public static class Nacos {

        private String serverAddr = "127.0.0.1:8848";
        /** 页面初始选中的命名空间；为空时默认取本机 ops 目录 env.properties 中的 env 值 */
        private String namespace = "";
        private String group = "DEFAULT_GROUP";
        /** Nacos 开启鉴权时的登录用户名；为空表示匿名访问 */
        private String username = "";
        /** Nacos 开启鉴权时的登录密码 */
        private String password = "";
        /**
         * 服务端身份标识 key（对应 Nacos 配置 nacos.core.auth.server.identity.key）。
         * Nacos 3.x 中 admin 接口即使 auth.enabled=false 也要求鉴权（auth.admin.enabled=true），
         * 携带该 header 即可通过，免去用户名密码登录。
         */
        private String identityKey = "server.identity";
        /** 服务端身份标识 value（对应 nacos.core.auth.server.identity.value） */
        private String identityValue = "";

        public String getServerAddr() {
            return serverAddr;
        }

        public void setServerAddr(String serverAddr) {
            this.serverAddr = serverAddr;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getIdentityKey() {
            return identityKey;
        }

        public void setIdentityKey(String identityKey) {
            this.identityKey = identityKey;
        }

        public String getIdentityValue() {
            return identityValue;
        }

        public void setIdentityValue(String identityValue) {
            this.identityValue = identityValue;
        }
    }
}
