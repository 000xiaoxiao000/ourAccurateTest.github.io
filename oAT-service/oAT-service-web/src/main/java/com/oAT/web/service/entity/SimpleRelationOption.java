package com.oAT.web.service.entity;

import java.io.Serializable;

public class SimpleRelationOption implements Serializable {
    private String id;
    private String name;
    private String url;
    private boolean external;

    public SimpleRelationOption() {
    }

    public SimpleRelationOption(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public SimpleRelationOption(String id, String name, String url, boolean external) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.external = external;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isExternal() {
        return external;
    }

    public void setExternal(boolean external) {
        this.external = external;
    }
}
