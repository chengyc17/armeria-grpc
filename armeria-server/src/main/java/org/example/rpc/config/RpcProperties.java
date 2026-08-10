package org.example.rpc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rpc")
public class RpcProperties {

    private int port = 8080;
    private boolean schemaEnabled = true;

    private final Nacos nacos = new Nacos();
    private final Client client = new Client();

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isSchemaEnabled() { return schemaEnabled; }
    public void setSchemaEnabled(boolean schemaEnabled) { this.schemaEnabled = schemaEnabled; }

    public Nacos getNacos() { return nacos; }
    public Client getClient() { return client; }

    public static class Nacos {
        private String serverAddr = "127.0.0.1:8848";
        private String namespace = "";

        public String getServerAddr() { return serverAddr; }
        public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
    }

    public static class Client {
        /** Data center identifier (e.g. "bj", "sh", "gz"). */
        private String idc = "";
        /** Kubernetes deployment / group name. Set via rpc.client.group-name in application.yml. */
        private String groupName = "";

        public String getIdc() { return idc; }
        public void setIdc(String idc) { this.idc = idc; }

        public String getGroupName() { return groupName; }
        public void setGroupName(String groupName) { this.groupName = groupName; }
    }
}
