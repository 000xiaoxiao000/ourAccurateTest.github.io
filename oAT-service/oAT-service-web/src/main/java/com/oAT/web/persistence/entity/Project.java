package com.oAT.web.persistence.entity;


import java.io.Serializable;

public class Project implements Serializable {
    //TODO 格式不统一
    private String name;    // 项目名称

    private String describe;// 项目描述

    private String create;   // 项目创建人

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescribe() {
        return describe;
    }

    public void setDescribe(String describe) {
        this.describe = describe;
    }

    public String getCreate() {
        return create;
    }

    public void setCreate(String create) {
        this.create = create;
    }

}
