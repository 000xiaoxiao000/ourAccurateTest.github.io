package com.oAT.web.service.entity;

import java.io.Serializable;

public class DirectoryDeleteResult implements Serializable {
    private boolean requiresCascade;
    private int directoryCount;
    private int usecaseCount;
    private boolean deleted;
    private String message;

    public boolean isRequiresCascade() {
        return requiresCascade;
    }

    public void setRequiresCascade(boolean requiresCascade) {
        this.requiresCascade = requiresCascade;
    }

    public int getDirectoryCount() {
        return directoryCount;
    }

    public void setDirectoryCount(int directoryCount) {
        this.directoryCount = directoryCount;
    }

    public int getUsecaseCount() {
        return usecaseCount;
    }

    public void setUsecaseCount(int usecaseCount) {
        this.usecaseCount = usecaseCount;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
