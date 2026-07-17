package com.oAT.web.service;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.oAT.web.service.entity.*;

public interface GitService {
    List<String> getRemoteBranches(String repoUrl, String username, String password);

    void checkGitPull(String repoUrl, String username, String password, String branch, String commitId);

    String getLatestCommitId(String repoUrl, String username, String password, String branch);

    List<GitCommitOptionVo> getRecentCommits(String repoUrl, String username, String password, String branch, int limit);

    GitPullEstimateVo estimateGitPull(String repoUrl, String username, String password, String branch, String commitId, String excludePaths);

    void downloadAndPackage(String repoUrl, String username, String password, String branch, String commitId, File targetZipFile);

    String startGitPullJob(String repoUrl, String username, String password, String branch, String commitId, String excludePaths);

    GitJobVo getGitJob(String jobId);

    void deleteCache(String cachePath);

    /**
     * 获取两个 commit 之间的差异
     * @return List<GitDiffVo>
     */
    List<GitDiffVo> getDiffDetail(String repoUrl, String username, String password, String oldCommit, String newCommit);

    /**
     * 获取指定 commit 下某个文件的内容（文本）。
     * 返回 null 表示文件在该 commit 中不存在或不可读。
     */
    String getFileContent(String repoUrl, String username, String password, String commitId, String filePath);

    /**
     * 批量获取指定 commit 下多个文件的内容，避免逐文件重复打开和同步仓库。
     */
    Map<String, String> getFileContents(String repoUrl, String username, String password, String commitId, Collection<String> filePaths);

    /**
     * 查找磁盘上已存在的缓存文件信息（基于分支和CommitID）。
     * 返回 null 表示不存在匹配的缓存文件。
     */
    GitCacheInfo findExistingCache(String branch, String commitId, String excludePaths);
}
