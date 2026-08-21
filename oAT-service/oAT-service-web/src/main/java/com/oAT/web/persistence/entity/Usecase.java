package com.oAT.web.persistence.entity;


import java.io.Serializable;

public class Usecase implements Serializable, StandardDate{

    /**
    标题
     */
    private String title;
    /**
    主题图片
     */
    private String headImage;
    /**
     内容
    */
    private String content;
    private String projectId;
    /**
     * 归属系统（App）ID。A1：需求全系统级，创建时必填。
     */
    private String appId;
    /**
     * 关联的其他系统 ID 列表（跨系统业务需求）。主系统为 appId，此处补充可见范围。可空。
     */
    private String relatedAppIds[];
    /**
     * 目录 ID
     */
    private String directory;
    /**
     * 绑定的测试缺陷id
     */
    private String defects[];
    /**
     * 绑定的PRD需求id
     */
    private String prdRequirements[];
    /**
    标签
     */
    private String labels[];
    /**
     作者
     */
    private String authors[];
    /**
     最后修改人
     */
    private String lastUpdateAuthor;
    /**
     * 是否开放共享访问
     */
    private Boolean share;

    // 该字段值有可能为超出256 keyword 的限制
    // 执行的源代码堆栈 格式：类名 方法名 方法签名 示例如下：
    //org/eclipse/jetty/servlet/DefaultServlet doGet (Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V
    private String srcStack[];


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

    public String[] getSrcStack() {
        return srcStack;
    }

    public void setSrcStack(String[] srcStack) {
        this.srcStack = srcStack;
    }

}
