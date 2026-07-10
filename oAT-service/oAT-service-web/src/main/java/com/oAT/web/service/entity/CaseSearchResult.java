package com.oAT.web.service.entity;

import java.util.Arrays;
import java.util.Date;
import java.io.Serializable;

public class CaseSearchResult implements Serializable {
    private String id;
    private String projectId;
    // 标题
    private String title;
    //  主题 图片
    private String headImage;
    // 标题高亮片段
    private String titleFragment;
    // 高亮片段
    private String[] contentFragments;
    // sql 高亮片段
    private String[] sqlContentFragments;
    // 远程调用高亮片段
    private String[] remoteContentFragments;
    private Date updateTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setTitleFragment(String titleFragment) {
        this.titleFragment = titleFragment;
    }

    public String[] getContentFragments() {
        return contentFragments;
    }

    public void setContentFragments(String[] contentFragments) {
        this.contentFragments = contentFragments;
    }

    public String[] getSqlContentFragments() {
        return sqlContentFragments;
    }

    public void setSqlContentFragments(String[] sqlContentFragments) {
        this.sqlContentFragments = sqlContentFragments;
    }

    public String getTitleFragment() {
        return titleFragment;
    }

    public String[] getRemoteContentFragments() {
        return remoteContentFragments;
    }

    public void setRemoteContentFragments(String[] remoteContentFragments) {
        this.remoteContentFragments = remoteContentFragments;
    }

    public String getHeadImage() {
        return headImage;
    }

    public void setHeadImage(String headImage) {
        this.headImage = headImage;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "CaseSearchResult{" +
               "id='" + id + '\'' +
               ", projectId='" + projectId + '\'' +
               ", title='" + title + '\'' +
               ", contentFragments=" + Arrays.toString(contentFragments) +
               ", sqlContentFragments=" + Arrays.toString(sqlContentFragments) +
               ", remoteContentFragments=" + Arrays.toString(remoteContentFragments) +
               ", updateTime=" + updateTime +
               '}';
    }
}
