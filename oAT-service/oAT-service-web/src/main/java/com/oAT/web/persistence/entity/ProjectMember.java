package com.oAT.web.persistence.entity;


import java.io.Serializable;

public class ProjectMember implements Serializable {
    private String projectId;

    private String memberId; // 用户ID

    private String role;// 权限角色

    private Boolean star;//是否为该用户收藏项目

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public ProjectMember() {
    }

    public ProjectMember(String projectId, String memberId, String role) {
        this.projectId = projectId;
        this.memberId = memberId;
        this.role = role;
        star=false;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }


    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Boolean getStar() {
        return star;
    }

    public void setStar(Boolean star) {
        this.star = star;
    }

}
