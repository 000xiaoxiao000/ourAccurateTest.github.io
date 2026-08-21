package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.Date;

public class UsecaseVo implements Serializable {
    private String id;
    private String projectId;
    // 归属系统（App）ID，A1 全系统级，创建时必填
    private String appId;
    // 关联的其他系统 ID 列表（跨系统业务需求），可空
    private String relatedAppIds[];
    // 标题
    private String title;
    // 标题图
    private String headImage;
    // 内容
    private String content;
    // 目录
    private String directory;
    // 绑定的测试缺陷id
    private String defects[];
    // 绑定的PRD需求id
    private String prdRequirements[];
    // 多行录入的测试缺陷文本
    private String defectsText;
    // 多行录入的PRD需求文本
    private String prdRequirementsText;
    //  标签
    private String labels[];
    //  作者
    private String authors[];
    // 最后更新作者
    private String lastUpdateAuthor;
    private Date createTime;
    private Date updateTime;
    private String updateTimeText;
    // 是否开放共享访问
    private Boolean share;


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

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String[] getRelatedAppIds() {
        return relatedAppIds;
    }

    public void setRelatedAppIds(String[] relatedAppIds) {
        this.relatedAppIds = relatedAppIds;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getHeadImage() {
        return headImage;
    }

    public void setHeadImage(String headImage) {
        this.headImage = headImage;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public String[] getDefects() {
        return defects;
    }

    public void setDefects(String[] defects) {
        this.defects = defects;
    }

    public String[] getPrdRequirements() {
        return prdRequirements;
    }

    public void setPrdRequirements(String[] prdRequirements) {
        this.prdRequirements = prdRequirements;
    }

    public String getDefectsText() {
        return defectsText;
    }

    public void setDefectsText(String defectsText) {
        this.defectsText = defectsText;
    }

    public String getPrdRequirementsText() {
        return prdRequirementsText;
    }

    public void setPrdRequirementsText(String prdRequirementsText) {
        this.prdRequirementsText = prdRequirementsText;
    }

    public String[] getLabels() {
        return labels;
    }

    public void setLabels(String[] labels) {
        this.labels = labels;
    }

    public String[] getAuthors() {
        return authors;
    }

    public void setAuthors(String[] authors) {
        this.authors = authors;
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

    public String getUpdateTimeText() {
        return updateTimeText;
    }

    public void setUpdateTimeText(String updateTimeText) {
        this.updateTimeText = updateTimeText;
    }

    public String getLastUpdateAuthor() {
        return lastUpdateAuthor;
    }

    public void setLastUpdateAuthor(String lastUpdateAuthor) {
        this.lastUpdateAuthor = lastUpdateAuthor;
    }

    public Boolean getShare() {
        return share;
    }

    public void setShare(Boolean share) {
        this.share = share;
    }

}
