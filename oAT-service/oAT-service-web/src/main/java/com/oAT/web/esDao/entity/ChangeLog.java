package com.oAT.web.esDao.entity;


import java.io.Serializable;
import java.util.Date;

public class ChangeLog implements Serializable,StandardDate {
    private String userId;
    private Date time;
    private String type;
    private String content;

    public ChangeLog(String userId, Date time, String type, String content) {
        this.userId = userId;
        this.time = time;
        this.type = type;
        this.content = content;
    }

    public ChangeLog() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Date getTime() {
        return time;
    }

    public void setTime(Date time) {
        this.time = time;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
