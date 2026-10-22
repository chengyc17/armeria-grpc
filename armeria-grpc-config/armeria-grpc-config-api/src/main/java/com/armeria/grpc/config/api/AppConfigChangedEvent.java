package com.armeria.grpc.config.api;

import org.springframework.context.ApplicationEvent;

/**
 * 配置变更事件：任一配置源（nacos / apollo）的某个 dataId 内容变化时发布。
 * 上层（治理等）监听本事件即可热更新，无需感知具体配置中心。
 */
public class AppConfigChangedEvent extends ApplicationEvent {

    private final String dataId;
    private final String previous;
    private final String current;

    public AppConfigChangedEvent(Object source, String dataId, String previous, String current) {
        super(source);
        this.dataId = dataId;
        this.previous = previous;
        this.current = current;
    }

    public String getDataId() {
        return dataId;
    }

    public String getPrevious() {
        return previous;
    }

    public String getCurrent() {
        return current;
    }
}
