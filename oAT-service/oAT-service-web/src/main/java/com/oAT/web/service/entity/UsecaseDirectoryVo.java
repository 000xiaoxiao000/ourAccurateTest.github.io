package com.oAT.web.service.entity;

import java.util.Date;

public class UsecaseDirectoryVo {
    private String id;
    private String name;
    private String parentId;
    private String projectId;
    private Date updateTime;
    private String updateTimeText;
    private String updateTimeRelativeText;

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

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getUpdateTimeText() {
        return updateTimeText;
    }

    public void setUpdateTimeText(String updateTimeText) {
        this.updateTimeText = updateTimeText;
    }

    public String getUpdateTimeRelativeText() {
        return updateTimeRelativeText;
    }

    public void setUpdateTimeRelativeText(String updateTimeRelativeText) {
        this.updateTimeRelativeText = updateTimeRelativeText;
    }
}
