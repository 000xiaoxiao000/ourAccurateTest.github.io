package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.Date;

public class ProjectMemberVo implements Serializable {
    private String id;
    /**
     * 项目id
     */
    private String projectId;
    /**
     * 用户ID
     */
    private String memberId;
    /**
     * 用户账户名称
     */
    private String memberName;
    /**
     * 账户email
     */
    private String memberEmail;
    /**
     * 权限角色
     */
    private Role role;
    /**
     * 是否为该用户收藏项目
     */
    private Boolean star;
    private Date createTime;
    /**
     * 是否为该用户默认项目
     */
    private Boolean defaultProject;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Boolean getDefaultProject() {
        return defaultProject;
    }

    public void setDefaultProject(Boolean defaultProject) {
        this.defaultProject = defaultProject;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }


    public String getMemberEmail() {
        return memberEmail;
    }

    public void setMemberEmail(String memberEmail) {
        this.memberEmail = memberEmail;
    }

    public Boolean getStar() {
        return star;
    }

    public void setStar(Boolean star) {
        this.star = star;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public enum Role {
        owner //项目所有者
        , admin //管理员
        , normal // 普通成员
        , visitor; // 访客
    }
}
