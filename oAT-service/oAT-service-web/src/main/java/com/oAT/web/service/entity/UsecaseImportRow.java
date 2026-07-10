package com.oAT.web.service.entity;

import com.alibaba.excel.annotation.ExcelProperty;

public class UsecaseImportRow {

    @ExcelProperty("用例名称（必填）")
    private String title;

    @ExcelProperty("所属目录（可选，空则导入当前目录）")
    private String directory;

    @ExcelProperty("详情内容")
    private String content;

    @ExcelProperty("标签")
    private String labels;

    @ExcelProperty("测试缺陷")
    private String defects;

    @ExcelProperty("PRD需求")
    private String prdRequirements;

    @ExcelProperty("封面图资源路径")
    private String headImage;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getLabels() {
        return labels;
    }

    public void setLabels(String labels) {
        this.labels = labels;
    }

    public String getDefects() {
        return defects;
    }

    public void setDefects(String defects) {
        this.defects = defects;
    }

    public String getPrdRequirements() {
        return prdRequirements;
    }

    public void setPrdRequirements(String prdRequirements) {
        this.prdRequirements = prdRequirements;
    }

    public String getHeadImage() {
        return headImage;
    }

    public void setHeadImage(String headImage) {
        this.headImage = headImage;
    }
}
