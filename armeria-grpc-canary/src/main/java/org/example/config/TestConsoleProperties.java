package org.example.config;

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
        private String namespace = "";
        private String group = "DEFAULT_GROUP";

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
    }
}
