package com.oAT.web.service.impl;

import com.oAT.web.common.Job;
import com.oAT.web.infra.git.GitPullJobWorkerService;
import com.oAT.web.infra.git.GitRemoteSupportService;
import com.oAT.web.infra.git.GitRepositoryContentService;
import com.oAT.web.service.GitService;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.entity.GitCacheInfo;
import com.oAT.web.service.entity.GitCommitOptionVo;
import com.oAT.web.service.entity.GitDiffVo;
import com.oAT.web.service.entity.GitJobVo;
import com.oAT.web.service.entity.GitPullEstimateVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GitServiceImpl implements GitService {

    private static final Logger logger = LoggerFactory.getLogger(GitServiceImpl.class);

    @Autowired
    private ResourceService resourceService;

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<String, Job<GitJobVo>> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> runningTaskMap = new ConcurrentHashMap<>();

    @Autowired
    private GitRemoteSupportService gitRemoteSupportService;

    @Autowired
    private GitRepositoryContentService gitRepositoryContentService;

    @Autowired
    private GitPullJobWorkerService gitPullJobWorkerService;

    @Override
    public List<String> getRemoteBranches(String repoUrl, String username, String password) {
        return gitRemoteSupportService.getRemoteBranches(repoUrl, username, password);
    }

    @Override
    public void checkGitPull(String repoUrl, String username, String password, String branch, String commitId) {
        gitRemoteSupportService.checkGitPull(repoUrl, username, password, branch, commitId);
    }

    @Override
    public String getLatestCommitId(String repoUrl, String username, String password, String branch) {
        return gitRemoteSupportService.getLatestCommitId(repoUrl, username, password, branch);
    }

    @Override
    public List<GitCommitOptionVo> getRecentCommits(String repoUrl, String username, String password, String branch, int limit) {
        return gitRemoteSupportService.getRecentCommits(repoUrl, username, password, branch, limit);
    }

    @Override
    public GitPullEstimateVo estimateGitPull(String repoUrl, String username, String password, String branch, String commitId, String excludePaths) {
        GitPullEstimateVo estimate = new GitPullEstimateVo();
        estimate.setBranch(branch);
        estimate.setCommitId(commitId);

        String normalizedRepoUrl = gitRemoteSupportService.normalizeRemoteUrl(repoUrl);
        String finalBranch = branch != null ? branch.trim() : "";
        String finalCommitId = commitId != null ? commitId.trim() : "";
        String taskKey = normalizedRepoUrl + "#" + finalBranch + "#" + finalCommitId + (StringUtils.hasText(excludePaths) ? "#" + excludePaths : "");
        String existingJobId = runningTaskMap.get(taskKey);
        if (existingJobId != null) {
            Job<GitJobVo> existingJob = jobs.get(existingJobId);
            if (existingJob != null && existingJob.getData() != null) {
                GitJobVo data = existingJob.getData();
                if (data.getPullDurationMs() != null) {
                    estimate.setEstimatedDurationMs(data.getPullDurationMs());
                }
                if (data.getPackageSizeBytes() != null) {
                    estimate.setEstimatedPackageSizeBytes(data.getPackageSizeBytes());
                }
            }
        }

        if (estimate.getEstimatedDurationMs() == null) {
            estimate.setEstimatedDurationMs(30000L);
        }
        if (estimate.getEstimatedPackageSizeBytes() == null) {
            estimate.setEstimatedPackageSizeBytes(50L * 1024 * 1024);
        }
        return estimate;
    }

    @Override
    public void downloadAndPackage(String repoUrl, String username, String password, String branch, String commitId, File targetZipFile) {
        gitPullJobWorkerService.downloadAndPackage(repoUrl, username, password, branch, commitId, targetZipFile);
    }

    @Override
    public String startGitPullJob(String repoUrl, String username, String password, String branch, String commitId, String excludePaths) {
        final String normalizedRepoUrl = gitRemoteSupportService.normalizeRemoteUrl(repoUrl);
        final String finalBranch = branch != null ? branch.trim() : "";
        final String finalCommitId = commitId != null ? commitId.trim() : "";
        String taskKey = normalizedRepoUrl + "#" + finalBranch + "#" + finalCommitId + (StringUtils.hasText(excludePaths) ? "#" + excludePaths : "");
        String existingJobId = runningTaskMap.get(taskKey);
        if (existingJobId != null) {
            Job<GitJobVo> existingJob = jobs.get(existingJobId);
            if (existingJob != null) {
                // 如果任务正在运行，或者已经成功完成且缓存文件依然存在，则复用
                if (existingJob.state == Job.JobState.active || existingJob.state == Job.JobState.wait) {
                    return existingJobId;
                }
                if (existingJob.state == Job.JobState.finish && existingJob.getData().isSuccess()) {
                    String cachePath = existingJob.getData().getCachePath();
                    if (cachePath != null && new File(resourceService.getCacheRoot(), cachePath).exists()) {
                        return existingJobId;
                    }
                }
            }
        }

        GitJobVo gitJobVo = new GitJobVo();
        Job<GitJobVo> job = new Job<>(gitJobVo);
        jobs.put(job.getId(), job);
        runningTaskMap.put(taskKey, job.getId());

        executorService.submit(() -> gitPullJobWorkerService.runPullJob(
                taskKey, job, normalizedRepoUrl, username, password, finalBranch, finalCommitId, excludePaths, runningTaskMap));

        return job.getId();
    }

    @Override
    public GitJobVo getGitJob(String jobId) {
        Job<GitJobVo> job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        GitJobVo vo = job.getData();
        vo.setId(job.getId()); // ensure ID is set
        if (job.getProgress() != null) {
            vo.setProgress(job.getProgress().getPercent());
            vo.setProgressName(job.getProgress().getName());
        }
        return vo;
    }

    @Override
    public void deleteCache(String cachePath) {
        if (!StringUtils.hasText(cachePath)) return;
        gitPullJobWorkerService.deleteCacheFile(cachePath);

        // 清理任务映射和任务对象，“删除后可再拉取”
        // 移到 if 外面是为了保证即使物理文件提前消失也能重置内存状态。
        runningTaskMap.entrySet().removeIf(entry -> {
            String jobId = entry.getValue();
            Job<GitJobVo> job = jobs.get(jobId);
            if (job != null && cachePath.equals(job.getData().getCachePath())) {
                jobs.remove(jobId); // 同时也从任务列表中移除，防止状态残留
                return true;
            }
            return false;
        });
    }

    @Override
    public List<GitDiffVo> getDiffDetail(String repoUrl, String username, String password, String oldCommit, String newCommit) {
        return gitRepositoryContentService.getDiffDetail(repoUrl, username, password, oldCommit, newCommit);
    }

    @Override
    public GitCacheInfo findExistingCache(String branch, String commitId, String excludePaths) {
        if (!StringUtils.hasText(branch) || !StringUtils.hasText(commitId)) {
            return null;
        }

        return gitPullJobWorkerService.findExistingCache(branch, commitId);
    }

    @Override
    public String getFileContent(String repoUrl, String username, String password, String commitId, String filePath) {
        return gitRepositoryContentService.getFileContent(repoUrl, username, password, commitId, filePath);
    }
}
