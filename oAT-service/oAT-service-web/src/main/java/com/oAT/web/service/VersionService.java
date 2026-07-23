package com.oAT.web.service;

import com.oAT.web.persistence.entity.VersionCompareReport;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.CompareJobVo;
import com.oAT.web.service.entity.VersionCompareReportVo;
import com.oAT.web.service.entity.VersionItemVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface VersionService {

    void addVersionItem(VersionItemVo itemVo);

    List<VersionItemVo> getVersionItemList(String projectId, String appId);

    Page<VersionItemVo> getVersionItemList(String projectId, String appId, Pageable pageable);

    VersionItemVo getLastVersionItem(String projectId, String appId);

    void doDeleteVersionItem(String id);

    String startCompareJob(String projectId, AppVo appinfo, String packageName, String sourceFile, String targetFile);

    CompareJobVo getCompareJob(String jobId);

    VersionCompareReport getCompareReport(String compareId);

    List<VersionCompareReportVo> getCompareReportList(String projectId, String appId);

    Page<VersionCompareReportVo> getCompareReportList(String projectId, String appId, Pageable pageable);

    void deleteCompareReport(String projectId, String reportId);

    void deleteCacheFile(String path);

    VersionItemVo getVersionByGitInfo(String appId, String versionNumber, String branch, String commitId);

    // Start a compare job using git diffs between two commits/branches (no build/checkout)
    String startCompareFromGit(String projectId, AppVo appinfo, String packageName, String branch, String oldCommit, String newCommit);
}
