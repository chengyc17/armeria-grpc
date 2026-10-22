package com.armeria.grpc.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rpc")
public class RpcProperties {

    private int port = 8080;
    private boolean schemaEnabled = true;

    private final Nacos nacos = new Nacos();

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isSchemaEnabled() { return schemaEnabled; }
    public void setSchemaEnabled(boolean schemaEnabled) { this.schemaEnabled = schemaEnabled; }

    public Nacos getNacos() { return nacos; }

    public static class Nacos {
        private String serverAddr = "";

        /** Instance scheduling weight registered to Nacos. Default 1.0 (Nacos default). */
        private double weight = 1.0;

        public String getServerAddr() { return serverAddr; }
        public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }

        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }
    }
}
