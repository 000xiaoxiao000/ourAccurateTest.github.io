package com.oAT.web.esDao.entity;

import org.springframework.data.annotation.Id;

import java.util.Date;

public class SystemIndex implements java.io.Serializable, StandardDate {
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss,SSS";
    //基础属性 ========================================
    @Id
    private String id;

    private String type;

    private Date createTime;

    private Date updateTime;

    //实体对象=================================================================
    private User user;

    private Project project;

    private App app;

    private LabelGroup labelGroup;

    /*
     项目成员
     */
    private ProjectMember projectMember;

    private SystemLog systemLog;

    /**
     * 框架反序列化使用。
     */
    public SystemIndex() {
    }


    public SystemIndex(User user) {
        this("user");
        this.user = user;
    }

    public SystemIndex(Project project) {
        this("project");
        this.project = project;
    }

    public SystemIndex(App app) {
        this("app");
        this.app = app;
    }

    public SystemIndex(LabelGroup labelGroup) {
        this("labelGroup");
        this.labelGroup = labelGroup;
    }

    public SystemIndex(ProjectMember projectMember) {
        this("projectMember");
        this.projectMember = projectMember;
    }

    public SystemIndex(SystemLog systemLog) {
        this("systemLog");
        this.systemLog = systemLog;
    }

    private SystemIndex(String type) {
        this.type = type;
        createTime = new Date();
        updateTime = new Date();
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }


    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public App getApp() {
        return app;
    }

    public void setApp(App app) {
        this.app = app;
    }

    public LabelGroup getLabelGroup() {
        return labelGroup;
    }

    public void setLabelGroup(LabelGroup labelGroup) {
        this.labelGroup = labelGroup;
    }

    public ProjectMember getProjectMember() {
        return projectMember;
    }

    public void setProjectMember(ProjectMember projectMember) {
        this.projectMember = projectMember;
    }

    public SystemLog getSystemLog() {
        return systemLog;
    }

    public void setSystemLog(SystemLog systemLog) {
        this.systemLog = systemLog;
    }
}
