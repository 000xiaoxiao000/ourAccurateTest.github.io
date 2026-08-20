package com.oAT.web.infra.git;

import com.oAT.web.common.FriendlyErrorMessageUtil;
import com.oAT.web.logging.LogFields;
import com.oAT.web.service.entity.GitCommitOptionVo;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

@Service
public class GitRemoteSupportService {
    private static final Logger logger = LoggerFactory.getLogger(GitRemoteSupportService.class);

    @Value("${git.default.username:}")
    private String defaultUsername;

    @Value("${git.default.password:}")
    private String defaultPassword;

    public UsernamePasswordCredentialsProvider credentials(String username, String password) {
        String finalUser = StringUtils.hasText(username) ? username : defaultUsername;
        String finalPass = StringUtils.hasText(password) ? password : defaultPassword;
        if (StringUtils.hasText(finalPass)) {
            return new UsernamePasswordCredentialsProvider(StringUtils.hasText(finalUser) ? finalUser : "git", finalPass);
        }
        return null;
    }

    public boolean hasDefaultPassword() {
        return StringUtils.hasText(defaultPassword);
    }

    public List<String> getRemoteBranches(String repoUrl, String username, String password) {
        List<String> branches = new ArrayList<>();
        String normalizedRepoUrl = normalizeRemoteUrl(repoUrl);
        try {
            LsRemoteCommand lsRemoteCommand = Git.lsRemoteRepository()
                    .setRemote(normalizedRepoUrl)
                    .setHeads(true)
                    .setTags(false);

            UsernamePasswordCredentialsProvider credentialsProvider = explicitOrDefaultCredentials(username, password);
            if (credentialsProvider != null) {
                lsRemoteCommand.setCredentialsProvider(credentialsProvider);
            }

            Collection<org.eclipse.jgit.lib.Ref> refs = lsRemoteCommand.call();
            for (org.eclipse.jgit.lib.Ref ref : refs) {
                String name = ref.getName();
                if (name.startsWith("refs/heads/")) {
                    branches.add(name.substring("refs/heads/".length()));
                }
            }
            Collections.sort(branches);
        } catch (Exception e) {
            logger.error("event=git.remote_branches.failed {}", LogFields.of(LogFields.map(
                    "repo_url_hash", hash(normalizedRepoUrl),
                    "reason", e.getMessage())), e);
            throw new RuntimeException("获取远程分支失败: " + friendlyError(e));
        }
        return branches;
    }

    public void checkGitPull(String repoUrl, String username, String password, String branch, String commitId) {
        String normalizedRepoUrl = normalizeRemoteUrl(repoUrl);
        String finalBranch = normalizeBranchName(branch);
        try {
            LsRemoteCommand lsRemoteCommand = Git.lsRemoteRepository()
                    .setRemote(normalizedRepoUrl)
                    .setHeads(true)
                    .setTags(true);

            UsernamePasswordCredentialsProvider credentialsProvider = credentials(username, password);
            if (credentialsProvider != null) {
                lsRemoteCommand.setCredentialsProvider(credentialsProvider);
            }

            Collection<org.eclipse.jgit.lib.Ref> refs = lsRemoteCommand.call();
            if (!StringUtils.hasText(finalBranch)) {
                selectDefaultHead(refs);
                if (StringUtils.hasText(commitId)) {
                    checkCommitIdExists(normalizedRepoUrl, username, password, commitId);
                }
                return;
            }

            boolean branchFound = false;
            String branchRef = "refs/heads/" + finalBranch;

            for (org.eclipse.jgit.lib.Ref ref : refs) {
                String name = ref.getName();
                if (name.equals(branchRef) || name.equals(finalBranch)) {
                    branchFound = true;
                    if (StringUtils.hasText(commitId) && ref.getObjectId().name().equalsIgnoreCase(commitId)) {
                        return;
                    }
                    break;
                }
            }

            if (!branchFound) {
                throw new RuntimeException("远程分支 " + finalBranch + " 不存在");
            }

            if (StringUtils.hasText(commitId)) {
                checkCommitIdExists(normalizedRepoUrl, username, password, commitId);
            }
        } catch (Exception e) {
            logger.error("event=git.pull_check.failed {}", LogFields.of(LogFields.map(
                    "repo_url_hash", hash(normalizedRepoUrl),
                    "branch", finalBranch,
                    "commit_id", shortCommit(commitId),
                    "reason", e.getMessage())), e);
            throw new RuntimeException("Git检测失败: " + friendlyError(e));
        }
    }

    public String getLatestCommitId(String repoUrl, String username, String password, String branch) {
        String normalizedRepoUrl = normalizeRemoteUrl(repoUrl);
        String finalBranch = normalizeBranchName(branch);
        try {
            LsRemoteCommand lsRemoteCommand = Git.lsRemoteRepository()
                    .setRemote(normalizedRepoUrl)
                    .setHeads(true)
                    .setTags(false);

            UsernamePasswordCredentialsProvider credentialsProvider = explicitOrDefaultCredentials(username, password);
            if (credentialsProvider != null) {
                lsRemoteCommand.setCredentialsProvider(credentialsProvider);
            }

            Collection<org.eclipse.jgit.lib.Ref> refs = lsRemoteCommand.call();
            if (!StringUtils.hasText(finalBranch)) {
                return selectDefaultHead(refs).getObjectId().name();
            }
            for (org.eclipse.jgit.lib.Ref ref : refs) {
                String name = ref.getName();
                if (name.equals("refs/heads/" + finalBranch)) {
                    return ref.getObjectId().name();
                }
            }
            throw new RuntimeException("分支 " + finalBranch + " 不存在");
        } catch (Exception e) {
            logger.error("event=git.latest_commit.failed {}", LogFields.of(LogFields.map(
                    "repo_url_hash", hash(normalizedRepoUrl),
                    "branch", finalBranch,
                    "reason", e.getMessage())), e);
            throw new RuntimeException("获取 CommitID失败: " + friendlyError(e));
        }
    }

    private org.eclipse.jgit.lib.Ref selectDefaultHead(Collection<org.eclipse.jgit.lib.Ref> refs) {
        if (refs == null || refs.isEmpty()) {
            throw new RuntimeException("仓库没有可用分支");
        }
        org.eclipse.jgit.lib.Ref firstHead = null;
        for (org.eclipse.jgit.lib.Ref ref : refs) {
            if (ref == null || ref.getObjectId() == null) {
                continue;
            }
            String name = ref.getName();
            if ("refs/heads/main".equals(name)) {
                return ref;
            }
            if (firstHead == null && name != null && name.startsWith("refs/heads/")) {
                firstHead = ref;
            }
        }
        for (org.eclipse.jgit.lib.Ref ref : refs) {
            if (ref == null || ref.getObjectId() == null) {
                continue;
            }
            if ("refs/heads/master".equals(ref.getName())) {
                return ref;
            }
        }
        if (firstHead != null) {
            return firstHead;
        }
        throw new RuntimeException("仓库没有可用分支");
    }

    public List<GitCommitOptionVo> getRecentCommits(String repoUrl, String username, String password, String branch, int limit) {
        List<GitCommitOptionVo> commits = new ArrayList<>();
        if (!StringUtils.hasText(branch)) {
            return commits;
        }

        String normalizedRepoUrl = normalizeRemoteUrl(repoUrl);
        int finalLimit = limit > 0 ? limit : 20;
        String finalBranch = normalizeBranchName(branch);
        String branchRef = "refs/heads/" + finalBranch;
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("oAT_git_commits");
            try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
                git.remoteAdd()
                        .setName("origin")
                        .setUri(new URIish(normalizedRepoUrl))
                        .call();

                FetchCommand fetch = git.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec(branchRef + ":refs/remotes/origin/" + finalBranch))
                        .setRemoveDeletedRefs(true);
                UsernamePasswordCredentialsProvider credentialsProvider = credentials(username, password);
                if (credentialsProvider != null) {
                    fetch.setCredentialsProvider(credentialsProvider);
                }
                fetch.call();

                ObjectId startId = resolveBranchHead(git.getRepository(), finalBranch);
                if (startId == null) {
                    throw new RuntimeException("分支 " + finalBranch + " 不存在或未拉取到提交对象");
                }
                Iterable<RevCommit> log = git.log().add(startId).setMaxCount(finalLimit).call();
                for (RevCommit commit : log) {
                    commits.add(toCommitOption(commit));
                }
            }
        } catch (Exception e) {
            logger.error("event=git.recent_commits.failed {}", LogFields.of(LogFields.map(
                    "repo_url_hash", hash(normalizedRepoUrl),
                    "branch", finalBranch,
                    "limit", finalLimit,
                    "reason", e.getMessage())), e);
            throw new RuntimeException("获取 Commit 列表失败: " + friendlyError(e));
        } finally {
            if (tempDir != null) {
                deleteFile(tempDir.toFile());
            }
        }
        return commits;
    }

    public String normalizeRemoteUrl(String repoUrl) {
        if (!StringUtils.hasText(repoUrl)) {
            return repoUrl;
        }

        String normalized = repoUrl.trim();
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String[] gitWebMarkers = {
                "/-/commits/", "/-/commit/", "/-/tree/", "/-/blob/", "/-/branches/",
                "/commits/", "/commit/", "/tree/", "/blob/", "/branches/"
        };
        for (String marker : gitWebMarkers) {
            int markerIndex = normalized.indexOf(marker);
            if (markerIndex > 0) {
                normalized = normalized.substring(0, markerIndex);
                break;
            }
        }
        if (normalized.endsWith("/-/branches")) {
            normalized = normalized.substring(0, normalized.length() - "/-/branches".length());
        }

        if (!normalized.equals(repoUrl.trim())) {
            logger.debug("event=git.remote_url.normalized {}", LogFields.of(LogFields.map(
                    "source_hash", hash(repoUrl.trim()),
                    "normalized_hash", hash(normalized))));
        }

        return normalized;
    }

    public String normalizeBranchName(String branch) {
        String value = branch == null ? "" : branch.trim();
        if (value.startsWith("refs/heads/")) {
            return value.substring("refs/heads/".length());
        }
        if (value.startsWith("refs/remotes/origin/")) {
            return value.substring("refs/remotes/origin/".length());
        }
        if (value.startsWith("origin/")) {
            return value.substring("origin/".length());
        }
        return value;
    }

    public String friendlyError(Exception e) {
        return FriendlyErrorMessageUtil.git(e);
    }

    private UsernamePasswordCredentialsProvider explicitOrDefaultCredentials(String username, String password) {
        if (StringUtils.hasText(password)) {
            String user = StringUtils.hasText(username) ? username : "git";
            return new UsernamePasswordCredentialsProvider(user, password);
        }
        return hasDefaultPassword() ? credentials(username, password) : null;
    }

    private void checkCommitIdExists(String repoUrl, String username, String password, String commitId) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("oAT_git_check_commit");
            try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
                git.remoteAdd()
                        .setName("origin")
                        .setUri(new URIish(repoUrl))
                        .call();

                FetchCommand fetch = git.fetch();
                fetch.setRemote("origin");
                fetch.setRefSpecs(new RefSpec(commitId));
                fetch.setDryRun(true);
                UsernamePasswordCredentialsProvider credentialsProvider = credentials(username, password);
                if (credentialsProvider != null) {
                    fetch.setCredentialsProvider(credentialsProvider);
                }
                fetch.call();
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("not found") || msg.contains("couldn't find remote ref") || msg.contains("Invalid remote"))) {
                throw new RuntimeException("Commit ID '" + commitId + "' 在远程仓库中不存在");
            }
            throw new RuntimeException("验证 Commit ID 失败: " + e.getMessage());
        } finally {
            if (tempDir != null) {
                deleteFile(tempDir.toFile());
            }
        }
    }

    private ObjectId resolveBranchHead(Repository repository, String branch) throws IOException {
        List<String> candidates = Arrays.asList(
                "refs/remotes/origin/" + branch,
                "refs/heads/" + branch,
                "origin/" + branch,
                branch
        );
        for (String candidate : candidates) {
            ObjectId objectId = repository.resolve(candidate);
            if (objectId != null) {
                return objectId;
            }
        }
        return null;
    }

    private GitCommitOptionVo toCommitOption(RevCommit commit) {
        String commitId = commit.getName();
        String shortCommitId = commitId.length() > 8 ? commitId.substring(0, 8) : commitId;
        String message = commit.getShortMessage();
        if (!StringUtils.hasText(message)) {
            message = "-";
        }
        String author = commit.getAuthorIdent() != null ? commit.getAuthorIdent().getName() : "";
        GitCommitOptionVo vo = new GitCommitOptionVo(commitId, shortCommitId, message, author);
        long whenMs = commit.getCommitTime() * 1000L;
        vo.setCommitTimestampMs(whenMs);
        vo.setCommitTimeText(formatCommitTime(whenMs));
        return vo;
    }

    private static String formatCommitTime(long whenMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(whenMs));
    }

    private void deleteFile(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File currentFile : files) {
                    deleteFile(currentFile);
                }
            }
        }
        if (!file.delete()) {
            logger.warn("event=file.delete_failed {}", LogFields.of(LogFields.map(
                    "file_path_hash", hash(file.getAbsolutePath()),
                    "directory", file.isDirectory())));
        }
    }

    private String hash(String value) {
        return Integer.toHexString(String.valueOf(value).hashCode());
    }

    private String shortCommit(String commitId) {
        if (!StringUtils.hasText(commitId)) {
            return "-";
        }
        String trimmed = commitId.trim();
        return trimmed.length() <= 8 ? trimmed : trimmed.substring(0, 8);
    }
}
