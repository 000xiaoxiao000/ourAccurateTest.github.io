package com.oAT.web.esDao.entity;


import java.io.Serializable;

public class File implements Serializable {
    private String name;
    private String type;
    private String key;
    private String path;
    private long length;

    public File() {
    }

    public File(String name, String type, String key, long length) {
        this.name = name;
        this.type = type;
        this.key = key;
        this.length = length;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
