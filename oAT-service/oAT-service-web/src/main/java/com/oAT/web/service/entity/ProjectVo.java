package com.oAT.web.service.entity;

import java.io.Serializable;

import java.util.Date;

public class ProjectVo implements Serializable {

    private String id;

    /**

     * 项目名称

     */

    private String name;

    /**

     * 项目描述

     */

    private String describe;

    /**

     * 项目创建人

     */

    private String create;

    /**

     * 项目创建人展示名称

     */

    private String createDisplayName;

    /**

     * 成员数

     */

    private int memberCount;

    private Date createTime;

    private Date updateTime;



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



    public String getDescribe() {

        return describe;

    }



    public void setDescribe(String describe) {

        this.describe = describe;

    }



    public String getCreate() {

        return create;

    }



    public void setCreate(String create) {

        this.create = create;

    }



    public String getCreateDisplayName() {
        return createDisplayName;
    }

    public void setCreateDisplayName(String createDisplayName) {
        this.createDisplayName = createDisplayName;
    }

    public int getMemberCount() {

        return memberCount;

    }



    public void setMemberCount(int memberCount) {

        this.memberCount = memberCount;

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

}
