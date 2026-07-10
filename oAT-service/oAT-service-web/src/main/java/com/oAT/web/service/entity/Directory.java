package com.oAT.web.service.entity;

public class Directory {
    private String path;
    private String name;
    private String id;

    public Directory(String path, String name, String id) {
        this.path = path;
        this.name = name;
        this.id = id;
    }

    public Directory() {
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
