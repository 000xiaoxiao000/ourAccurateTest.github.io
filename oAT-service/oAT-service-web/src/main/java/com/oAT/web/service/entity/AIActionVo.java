package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.Map;

public class AIActionVo implements Serializable {
    private String type;
    private String title;
    private String description;
    private String url;
    private Boolean requireConfirm;
    private String confirmText;
    private Map<String, Object> payload;

    public AIActionVo() {
    }

    public AIActionVo(String type, String title, String description, String url, Boolean requireConfirm, String confirmText) {
        this.type = type;
        this.title = title;
        this.description = description;
        this.url = url;
        this.requireConfirm = requireConfirm;
        this.confirmText = confirmText;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Boolean getRequireConfirm() {
        return requireConfirm;
    }

    public void setRequireConfirm(Boolean requireConfirm) {
        this.requireConfirm = requireConfirm;
    }

    public String getConfirmText() {
        return confirmText;
    }

    public void setConfirmText(String confirmText) {
        this.confirmText = confirmText;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}
