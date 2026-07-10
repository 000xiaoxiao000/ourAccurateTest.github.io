package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.Date;

public class GitCacheInfo implements Serializable {
    private String cachePath;
    private Long fileSizeBytes;
    private Date createTime;

    public GitCacheInfo() {
    }

    public GitCacheInfo(String cachePath, Long fileSizeBytes, Date createTime) {
        this.cachePath = cachePath;
        this.fileSizeBytes = fileSizeBytes;
        this.createTime = createTime;
    }

    public String getCachePath() {
        return cachePath;
    }

    public void setCachePath(String cachePath) {
        this.cachePath = cachePath;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
