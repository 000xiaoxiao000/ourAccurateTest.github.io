package com.oAT.web.service.impl;

import com.oAT.web.common.FriendlyErrorMessageUtil;
import com.oAT.web.common.Job;
import com.oAT.web.common.compare.CompareResult;
import com.oAT.web.common.compare.CompareUtils;
import com.oAT.web.persistence.entity.VersionCompareReport;
import com.oAT.web.exceptions.FriendlyException;
import com.oAT.web.domain.version.VersionGitDiffCompareService;
import com.oAT.web.domain.version.VersionItemCatalogService;
import com.oAT.web.domain.version.VersionCompareReportService;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.VersionService;
import com.oAT.web.service.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static java.util.concurrent.Executors.*;

@Service
public class VersionServiceImpl implements VersionService, InitializingBean {
    static Logger logger = LoggerFactory.getLogger(VersionServiceImpl.class);

    @Autowired
    ResourceService resourceService;

    @Autowired
    private com.oAT.web.service.GitService gitService;

    @Autowired
    VersionGitDiffCompareService versionGitDiffCompareService;

    @Autowired
    VersionCompareReportService versionCompareReportService;

    @Autowired
    VersionItemCatalogService versionItemCatalogService;

    private ExecutorService compareJobExecutors;
    private List<Job<CompareJobVo>> jobs;

    @Override
    public void addVersionItem(VersionItemVo itemVo) {
        versionItemCatalogService.addVersionItem(itemVo);
    }

    @Override
    public List<VersionItemVo> getVersionItemList(String projectId, String appId) {
        return versionItemCatalogService.getVersionItemList(projectId, appId);
    }

    @Override
    public Page<VersionItemVo> getVersionItemList(String projectId, String appId, Pageable pageable) {
        return versionItemCatalogService.getVersionItemList(projectId, appId, pageable);
    }

    @Override
    public VersionItemVo getLastVersionItem(String projectId, String appId) {
        return versionItemCatalogService.getLastVersionItem(projectId, appId);
    }

    @Override
    public void doDeleteVersionItem(String id) {
        versionItemCatalogService.deleteVersionItem(id);
    }

    @Override
    public VersionItemVo getVersionByGitInfo(String appId, String versionNumber, String branch, String commitId) {
        return versionItemCatalogService.getVersionByGitInfo(appId, versionNumber, branch, commitId);
    }

    @Override
    public void deleteCacheFile(String path) {
        versionItemCatalogService.deleteCacheFile(path);
    }

    @Override
    public String startCompareJob(String projectId, AppVo appinfo, String packageName, String sourceFile,
                                  String targetFile) {
        if (sourceFile == null || sourceFile.isEmpty() || targetFile == null || targetFile.isEmpty()) {
            return "";
        }
        CompareJobVo jobInfo = new CompareJobVo(sourceFile, targetFile);
        jobInfo.setProjectId(projectId);
        jobInfo.setAppId(appinfo.getId());
        jobInfo.setProgressName("等待启动");
        jobInfo.setName(String.format("%s 比对 %s",
                new File(sourceFile).getName(),
                new File(targetFile).getName()));
        final Job<CompareJobVo> job = new Job<>(jobInfo);

        Future<?> f = compareJobExecutors.submit(() -> {
            jobInfo.setFinish(false);
            try {
                job.getLogger().info("版本文件比对任务启动");
                job.getLogger().info(String.format("源文件: %s ｜ 目标文件: %s ｜ 包范围: %s",
                        sourceFile, targetFile, StringUtils.hasText(packageName) ? packageName : "*"));
                startCompareJobInternal(packageName, job);
            } catch (Exception e) {
                markCompareJobError(job, e, "版本文件比对失败，请检查选择的文件后重试。");
                job.state = Job.JobState.error;
                logger.error("版本文件比对失败:{}", job.getData(), e);
            } finally {
                if (job.state != Job.JobState.error) {
                    job.state = Job.JobState.finish;
                    jobInfo.setFinish(true);
                }
                new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                        jobs.remove(job);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("比对任务移除失败", e);
                    }
                }).start();
            }
        });
        jobInfo.setId(job.getId());
        job.setFuture(f);
        jobs.add(job);
        return job.getId();
    }

    @Override
    public String startCompareFromGit(String projectId, AppVo appinfo, String packageName, String branch,
                                      String oldCommit, String newCommit) {
        // Create a lightweight compare job that uses gitService.getDiff to obtain changed classes
        CompareJobVo jobInfo = new CompareJobVo(oldCommit == null ? ("git:" + newCommit) :
                (oldCommit + ".." + newCommit), "git-diff");
        jobInfo.setProjectId(projectId);
        jobInfo.setAppId(appinfo.getId());
        jobInfo.setProgressName("准备中");
        jobInfo.setName("Git 版本比对");
        // 保存 git 元信息到 job，用于后续保存到报告
        jobInfo.setGitBranch(branch);
        jobInfo.setGitOldCommit(oldCommit);
        jobInfo.setGitNewCommit(newCommit);
        // 将 commit 作为 source/target 字段，便于在报告中展示
        jobInfo.setSourceFile(oldCommit == null ? "" : oldCommit);
        jobInfo.setTargetFile(newCommit == null ? "" : newCommit);
        final Job<CompareJobVo> job = new Job<>(jobInfo);

        Future<?> f = compareJobExecutors.submit(() -> {
            job.state = Job.JobState.active;
            jobInfo.setFinish(false);
            try {
                job.getLogger().info("Git 版本比对任务启动");
                job.getLogger().info(String.format("分支: %s ｜ 旧: %s ｜ 新: %s", branch, shortCommit(oldCommit), shortCommit(newCommit)));
                job.setProgress(new Job.JobProgress());
                job.getProgress().next("获取Git差异", 50);

                job.getProgress().next("生成差异结果", 40);
                List<CompareResult> differences = versionGitDiffCompareService.buildGitDifferences(
                        job, appinfo, packageName, oldCommit, newCommit);

                job.getData().setDifferences(differences);
                countJobInfo(differences, job.getData());

                job.getLogger().info("跳过已移除的影响分析");
                job.setProgress(new Job.JobProgress());
                job.getProgress().next("整理差异结果", 80);
                job.getProgress().total = differences.size();
                job.getProgress().loaded = differences.size();

                job.getProgress().next("保存版本比对报告", 10);
                if (job.getData().getDifferences() == null) {
                    job.getData().setDifferences(new ArrayList<>());
                }
                saveCompareReport(job);
                job.getProgress().finish("比对完成");
                job.state = Job.JobState.finish;
            } catch (Exception e) {
                markCompareJobError(job, e, "版本比对失败，请检查仓库配置或稍后重试。");
                job.state = Job.JobState.error;
            } finally {
                if (job.state != Job.JobState.error) {
                    jobInfo.setFinish(true);
                }
                new Thread(() -> {
                    try {
                        Thread.sleep(15000);
                        jobs.remove(job);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("移除比对任务失败", e);
                    }
                }).start();
            }
        });

        jobInfo.setId(job.getId());
        job.setFuture(f);
        jobs.add(job);
        return job.getId();
    }

    @Override
    public CompareJobVo getCompareJob(String jobId) {
        if (jobId == null) return null;
        for (Job<CompareJobVo> job : jobs) {
            if (job.getId().equals(jobId)) {
                CompareJobVo jobVo = flushJobState(job);
                CompareJobVo result = new CompareJobVo();
                BeanUtils.copyProperties(jobVo, result);
                return result;
            }
        }
        return null;
    }

    private CompareJobVo flushJobState(Job<CompareJobVo> job) {
        CompareJobVo result = job.getData();
        result.setLog(job.getLog());
        if (job.getProgress() != null) {
            result.setProgress(job.getProgress().getPercent());
            result.setProgressName(job.getProgress().getName());
        }
        result.setFinish(job.state == Job.JobState.finish);
        result.setError(job.state == Job.JobState.error);
        return result;
    }

    private void markCompareJobError(Job<CompareJobVo> job, Exception e, String fallbackMessage) {
        String message = e instanceof FriendlyException ? e.getMessage() : fallbackMessage;
        if (e instanceof FriendlyException && !StringUtils.hasText(message)) {
            message = FriendlyErrorMessageUtil.git(e);
        }
        if (!StringUtils.hasText(message)) {
            message = fallbackMessage;
        }
        CompareJobVo jobData = job.getData();
        if (jobData != null) {
            jobData.setError(true);
            jobData.setFinish(false);
            jobData.setErrorMessage(message);
            jobData.setProgressName("比对失败");
        }
        if (job.getProgress() != null) {
            job.getProgress().updateName("比对失败");
        }
        job.getLogger().error(message);
        if (e != null && StringUtils.hasText(e.getMessage()) && !e.getMessage().equals(message)) {
            job.getLogger().error(e.getMessage());
        }
    }

    private void startCompareJobInternal(String packageName, Job<CompareJobVo> job) {
        if (packageName == null || packageName.isEmpty()) {
            packageName = "*";
        }
        job.state = Job.JobState.active;
        File parent = new File(resourceService.getCacheRoot());
        File sourceFile = new File(parent, job.getData().getSourceFile());
        File targetFile = new File(parent, job.getData().getTargetFile());

        CompareUtils compareUtils = new CompareUtils(job.getLogger(), job.getProgress());
        List<CompareResult> difference;
        try {
            difference = compareUtils.compareWar(packageName, sourceFile, targetFile);
            countJobInfo(difference, job.getData());
        } catch (IOException e) {
            markCompareJobError(job, e, "版本文件比对失败，请检查选择的文件后重试。");
            job.state = Job.JobState.error;
            logger.error("版本文件比对失败:{}", job.getData(), e);
            return;
        }
        job.getData().setDifferences(difference);

        job.getLogger().info(String.format("版本文件比较完成,发现差异项: %s", difference.size()));
        job.getLogger().info("跳过已移除的影响分析");
        job.setProgress(new Job.JobProgress());
        job.getProgress().next("整理差异结果", 80);
        job.getProgress().total = difference.size();
        job.getProgress().loaded = difference.size();
        // 保存版本比对报告
        job.getProgress().next("生成比对报告", 20);
        job.getLogger().info("开始生成比对报告");
        job.getProgress().total = 1;
        saveCompareReport(job);
        job.getProgress().loaded++;
        job.getLogger().info("比对报告已生成");
        job.getLogger().info("版本比对完成");
        job.getProgress().finish("对比完成");
        // 保存版本比对报告
        job.state = Job.JobState.finish;
    }

    private void saveCompareReport(Job<CompareJobVo> job) {
        flushJobState(job);
        versionCompareReportService.saveCompareReport(job);
    }

    private void countJobInfo(List<CompareResult> result, CompareJobVo jobInfo) {
        for (CompareResult compareResult : result) {
            switch (compareResult.getModel()) {
                case add:
                    jobInfo.addClassCount++;
                    break;
                case delete:
                    jobInfo.deleteClassCount++;
                    break;
                case update:
                    jobInfo.updateClassCount++;
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + compareResult.getModel());
            }
            for (CompareResult.Method method : compareResult.getMethods()) {
                switch (method.getModel()) {
                    case add:
                        jobInfo.addMethodCount++;
                        break;
                    case delete:
                        jobInfo.deleteMethodCount++;
                        break;
                    case update:
                        jobInfo.updateMethodCount++;
                        break;
                    default:
                        throw new IllegalStateException("Unexpected value: " + compareResult.getModel());
                }
            }
        }
    }

    @Override
    public void afterPropertiesSet() {
        compareJobExecutors = newFixedThreadPool(5);
        // 构建线程安全的任务对列
        jobs = Collections.synchronizedList(new LinkedList<>());
    }

    @Override
    public VersionCompareReport getCompareReport(String compareId) {
        return versionCompareReportService.getCompareReport(compareId);
    }

    @Override
    public List<VersionCompareReportVo> getCompareReportList(String projectId, String appId) {
        Pageable pageable = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createTime"));
        return getCompareReportList(projectId, appId, pageable).getContent();
    }

    @Override
    public Page<VersionCompareReportVo> getCompareReportList(String projectId, String appId, Pageable pageable) {
        return versionCompareReportService.getCompareReportList(projectId, appId, pageable);
    }

    @Override
    public void deleteCompareReport(String projectId, String reportId) {
        versionCompareReportService.deleteCompareReport(projectId, reportId);
    }

    private String shortCommit(String commitId) {
        if (!StringUtils.hasText(commitId)) {
            return "-";
        }
        String trimmed = commitId.trim();
        return trimmed.length() > 7 ? trimmed.substring(0, 7) : trimmed;
    }

}
