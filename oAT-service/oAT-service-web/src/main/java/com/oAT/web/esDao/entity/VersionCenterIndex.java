package com.oAT.web.esDao.entity;

import org.springframework.data.annotation.Id;

import java.io.Serializable;
import java.util.Date;

public class VersionCenterIndex implements Serializable, StandardDate {
    @Id
    private String id;
    private String type;
    private Date createTime;
    private Date updateTime;


    //实体对象=================================================================
    VersionItem versionItem;

    VersionCompareReport compareReport;

    public VersionCenterIndex() {

    }

    public VersionCenterIndex(VersionItem versionItem) {
        this.versionItem = versionItem;
        this.type = "versionItem";
        createTime = new Date();
        updateTime = new Date();
    }

    public VersionCenterIndex(VersionCompareReport compareReport) {
        this.compareReport = compareReport;
        this.type = "compareReport";
        createTime = new Date();
        updateTime = new Date();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public VersionItem getVersionItem() {
        return versionItem;
    }

    public void setVersionItem(VersionItem versionItem) {
        this.versionItem = versionItem;
    }

    public VersionCompareReport getCompareReport() {
        return compareReport;
    }

    public void setCompareReport(VersionCompareReport compareReport) {
        this.compareReport = compareReport;
    }
}
