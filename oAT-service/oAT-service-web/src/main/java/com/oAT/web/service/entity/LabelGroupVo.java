package com.oAT.web.service.entity;

import com.oAT.web.persistence.entity.LabelGroup;

import java.util.Date;

public class LabelGroupVo extends LabelGroup {
    private String id;
    private Date updateTime;

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
}
