package org.example.config;

import org.springframework.context.ApplicationEvent;

/**
 * Nacos 中 appId 对应配置发生变更时发布的事件。
 */
public class AppConfigChangedEvent extends ApplicationEvent {

    private final String dataId;
    private final String group;
    private final String oldContent;
    private final String newContent;

    public AppConfigChangedEvent(Object source, String dataId, String group,
                                 String oldContent, String newContent) {
        super(source);
        this.dataId = dataId;
        this.group = group;
        this.oldContent = oldContent;
        this.newContent = newContent;
    }

    public String getDataId() {
        return dataId;
    }

    public String getGroup() {
        return group;
    }

    public String getOldContent() {
        return oldContent;
    }

    public String getNewContent() {
        return newContent;
    }
}
