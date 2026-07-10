package com.oAT.web.control.entity;

import java.io.Serializable;

public class DirectoryDeletePreview implements Serializable {
    private boolean requiresCascade;
    private int directoryCount;
    private int usecaseCount;

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
}
