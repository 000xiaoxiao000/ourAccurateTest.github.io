package com.oAT.web.persistence.entity;

import org.springframework.data.annotation.Id;

import java.io.Serializable;
import java.util.Date;

/**
 * 用例中心索引
 */
public class CaseCenterIndex implements StandardDate {
    @Id
    private String id;
    private String type;
    private Date createTime;
    private Date updateTime;

    //实体对象=================================================================
    Usecase usecase;
    UsecaseDirectory directory;

    public CaseCenterIndex() {
    }

    public CaseCenterIndex(Usecase usecase) {
        this.usecase = usecase;
        this.type = "usecase";
        createTime = new Date();
        updateTime = new Date();
    }

    public CaseCenterIndex(UsecaseDirectory directory) {
        this.directory = directory;
        this.type = "directory";
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

    public Usecase getUsecase() {
        return usecase;
    }

    public void setUsecase(Usecase usecase) {
        this.usecase = usecase;
    }

    public UsecaseDirectory getDirectory() {
        return directory;
    }

    public void setDirectory(UsecaseDirectory directory) {
        this.directory = directory;
    }

}
