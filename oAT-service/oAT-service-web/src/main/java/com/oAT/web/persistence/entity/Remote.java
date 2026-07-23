package com.oAT.web.persistence.entity;


import java.io.Serializable;

/**
 * 远程调用
 */
public class Remote implements Serializable {
    private String type; //类别
    private String appId; // 远程应用id
    private String url;   // 远程应用地址
    private String invokerInterface; //远程应用接口与方法

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getInvokerInterface() {
        return invokerInterface;
    }

    public void setInvokerInterface(String invokerInterface) {
        this.invokerInterface = invokerInterface;
    }
}
