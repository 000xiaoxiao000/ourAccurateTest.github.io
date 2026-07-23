package com.oAT.web.persistence.entity;

import org.springframework.data.annotation.Id;

import java.util.Date;

/**
 * 静态源码数据索引
 */
public class StaticSourceInfo implements StandardDate {
    @Id
    private String id;
    private String appId;
    private String type;
    private Date createTime;
    private Date updateTime;

    // 实体对象
    private StaticSourceClassInfo classInfo;

    public StaticSourceInfo() {
    }

    public StaticSourceInfo(StaticSourceClassInfo classInfo) {
        this.classInfo = classInfo;
        this.type = "classInfo";
        createTime = new Date();
        updateTime = new Date();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public StaticSourceClassInfo getClassInfo() {
        return classInfo;
    }

    public void setClassInfo(StaticSourceClassInfo classInfo) {
        this.classInfo = classInfo;
    }
}
