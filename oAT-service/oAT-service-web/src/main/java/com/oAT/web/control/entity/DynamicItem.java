package com.oAT.web.control.entity;

import java.io.Serializable;
import java.util.Date;

public class DynamicItem implements Serializable {

    private String time;
    private String title;
    private String subTitle;
    private String describe;
    private String type;
    private Date date;

    public DynamicItem(String time, String title, String type) {
        this.time = time;
        this.title = title;
        this.type = type;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubTitle() {
        return subTitle;
    }

    public void setSubTitle(String subTitle) {
        this.subTitle = subTitle;
    }

    public String getDescribe() {
        return describe;
    }

    public void setDescribe(String describe) {
        this.describe = describe;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }

}
