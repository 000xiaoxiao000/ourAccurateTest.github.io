package com.oAT.web.persistence.entity;


import java.io.Serializable;

public class UsecaseRemote implements Serializable {
    /*
    远程调用原内容
     */
    private String[] content;
    /*
    dubbo远程调用 className.method
     */
    private String[] dubbo;
    /*
    http 远程调用 格式 host/uri
     */
    private String[] http;

    public String[] getDubbo() {
        return dubbo;
    }

    public void setDubbo(String[] dubbo) {
        this.dubbo = dubbo;
    }

    public String[] getHttp() {
        return http;
    }

    public void setHttp(String[] http) {
        this.http = http;
    }

    public String[] getContent() {
        return content;
    }

    public void setContent(String[] content) {
        this.content = content;
    }
}
