package com.oAT.web.service.entity;

import com.oAT.web.esDao.entity.SystemLog;

import java.io.Serializable;
import java.util.Date;

public class SystemLogVo extends SystemLog implements Serializable {
    private String id;
    private Date createTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
