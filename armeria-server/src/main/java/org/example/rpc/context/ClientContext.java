package org.example.rpc.context;

/**
 * Holds metadata about the calling client, propagated via gRPC headers.
 */
public class ClientContext {

    private final String clientIp;
    private final String hostname;
    private final String idc;
    private final String groupName;

    public ClientContext(String clientIp, String hostname, String idc, String groupName) {
        this.clientIp = clientIp;
        this.hostname = hostname;
        this.idc = idc;
        this.groupName = groupName;
    }

    public String getClientIp() { return clientIp; }
    public String getHostname() { return hostname; }
    public String getIdc() { return idc; }
    public String getGroupName() { return groupName; }

    @Override
    public String toString() {
        return "ClientContext{clientIp='" + clientIp + "', hostname='" + hostname
                + "', idc='" + idc + "', groupName='" + groupName + "'}";
    }
}
