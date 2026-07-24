package com.oAT.web.infra.git;

import com.oAT.web.common.SourceClassUtil;
import com.oAT.web.exceptions.FriendlyException;
import com.oAT.web.logging.LogFields;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.entity.GitDiffVo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class GitRepositoryContentService {
    private static final Logger logger = LoggerFactory.getLogger(GitRepositoryContentService.class);

    @Autowired
    private ResourceService resourceService;

    private final Map<String, ReentrantLock> fallbackRepoLocks = new ConcurrentHashMap<>();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GitRemoteSupportService gitRemoteSupportService;

    public List<GitDiffVo> getDiffDetail(String repoUrl, String username, String password, String oldCommit, String newCommit) {
        List<GitDiffVo> diffList = new ArrayList<>();
        if (!StringUtils.hasText(newCommit)) return diffList;

        String normalizedRepoUrl = gitRemoteSupportService.normalizeRemoteUrl(repoUrl);
        RepositoryLock lock = getRepoLock(normalizedRepoUrl);
        lock.lock();
        try {
            File gitCacheDir = getGitCacheDir(normalizedRepoUrl);
            Git clonedGit = openOrCloneBareWorktree(normalizedRepoUrl, username, password, gitCacheDir, true);
            try (Git git = clonedGit) {
                Repository repository = git.getRepository();
                ObjectId oldId = StringUtils.hasText(oldCommit) ? repository.resolve(oldCommit) : null;
                ObjectId newId = repository.resolve(newCommit);
                if (newId == null) {
                    logger.warn("event=git.diff.resolve_new_commit_failed {}", LogFields.of(LogFields.map(
                            "repo_url_hash", hash(normalizedRepoUrl),
                            "new_commit", shortCommit(newCommit))));
                    return diffList;
                }

                try (RevWalk walk = new RevWalk(repository)) {
                    RevCommit oldRev = oldId != null ? walk.parseCommit(oldId) : null;
                    RevCommit newRev = walk.parseCommit(newId);
                    try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                        formatter.setRepository(repository);
                        formatter.setDiffComparator(RawTextComparator.DEFAULT);
                        formatter.setDetectRenames(true);
                        List<DiffEntry> diffs = formatter.scan(oldRev == null ? null : oldRev.getTree(), newRev.getTree());
                        for (DiffEntry entry : diffs) {
                            String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
                            if (!isSupportedSourcePath(path)) {
                                continue;
                            }
                            GitDiffVo change = new GitDiffVo(toClassName(path), changedNewLines(formatter, entry),
                                    entry.getChangeType().name(), path);
                            change.setOldPath(entry.getOldPath());
                            change.setNewPath(entry.getNewPath());
                            change.setRenameScore(entry.getScore());
                            change.setOldBlobId(entry.getOldId() == null ? null : entry.getOldId().toObjectId().name());
                            change.setNewBlobId(entry.getNewId() == null ? null : entry.getNewId().toObjectId().name());
                            change.setOldRanges(changedRanges(formatter, entry, true));
                            change.setNewRanges(changedRanges(formatter, entry, false));
                            diffList.add(change);
                        }
                    }
                }
            }
            if (!gitCacheDir.setLastModified(System.currentTimeMillis())) {
                logger.debug("event=git.cache.touch_failed {}", LogFields.of(LogFields.map(
                        "directory_hash", hash(gitCacheDir.getAbsolutePath()))));
            }
        } catch (Exception e) {
            String friendlyMessage = gitRemoteSupportService.friendlyError(e);
            logger.error("event=git.diff_detail.failed {}", LogFields.of(LogFields.map(
                    "repo_url_hash", hash(normalizedRepoUrl),
                    "old_commit", shortCommit(oldCommit),
                    "new_commit", shortCommit(newCommit),
                    "reason", friendlyMessage)), e);
            throw new FriendlyException(friendlyMessage, e);
        } finally {
            lock.unlock();
        }
        return diffList;
    }

    public String getFileContent(String repoUrl, String username, String password, String commitId, String filePath) {
        return getFileContents(repoUrl, username, password, commitId, List.of(filePath)).get(filePath);
    }

    public Map<String, String> getFileContents(String repoUrl, String username, String password, String commitId, Collection<String> filePaths) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(commitId) || filePaths == null || filePaths.isEmpty()) return result;
        List<String> normalizedPaths = filePaths.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (normalizedPaths.isEmpty()) return result;
        String normalizedRepoUrl = gitRemoteSupportService.normalizeRemoteUrl(repoUrl);
        RepositoryLock lock = getRepoLock(normalizedRepoUrl);
        lock.lock();
        try {
            File gitCacheDir = getGitCacheDir(normalizedRepoUrl);
            Git clonedGit = openOrCloneBareWorktree(normalizedRepoUrl, username, password, gitCacheDir, false);
            try (Git git = clonedGit) {
                Repository repository = git.getRepository();
                ObjectId commitObj = repository.resolve(commitId);
                if (commitObj == null) {
                    try {
                        git.fetch().setCredentialsProvider(gitRemoteSupportService.credentials(username, password)).call();
                        commitObj = repository.resolve(commitId);
                    } catch (Exception e) {
                        logger.warn("event=git.file_content.fetch_for_commit_failed {}", LogFields.of(LogFields.map(
                                "repo_url_hash", hash(normalizedRepoUrl),
                                "commit_id", shortCommit(commitId),
                                "reason", e.getMessage())));
                    }
                    if (commitObj == null) {
                        logger.warn("event=git.file_content.resolve_commit_failed {}", LogFields.of(LogFields.map(
                                "repo_url_hash", hash(normalizedRepoUrl),
                                "commit_id", shortCommit(commitId))));
                        return result;
                    }
                }
                try (RevWalk revWalk = new RevWalk(repository)) {
                    RevCommit revCommit = revWalk.parseCommit(commitObj);
                    for (String filePath : normalizedPaths) {
                        Set<String> candidates = buildFileCandidates(filePath);
                        for (String candidate : candidates) {
                            String content = readExactPath(repository, revCommit, candidate);
                            if (content != null) {
                                result.put(filePath, content);
                                break;
                            }
                        }
                        if (!result.containsKey(filePath)) {
                            String content = readPathSuffix(repository, revCommit, candidates);
                            if (content != null) result.put(filePath, content);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("event=git.file_contents.failed {}", LogFields.of(LogFields.map(
                    "repo_url_hash", hash(normalizedRepoUrl),
                    "commit_id", shortCommit(commitId),
                    "file_count", normalizedPaths.size(),
                    "reason", e.getMessage())));
        } finally {
            lock.unlock();
        }
        return result;
    }

    private Git openOrCloneBareWorktree(String normalizedRepoUrl, String username, String password, File gitCacheDir, boolean fetchExisting) throws Exception {
        Git clonedGit;
        if (new File(gitCacheDir, ".git").exists()) {
            clonedGit = Git.open(gitCacheDir);
            if (fetchExisting) {
                try {
                    clonedGit.fetch().setCredentialsProvider(gitRemoteSupportService.credentials(username, password)).call();
                } catch (Exception e) {
                    logger.warn("event=git.cache.fetch_failed {}", LogFields.of(LogFields.map(
                            "repo_url_hash", hash(normalizedRepoUrl),
                            "reason", e.getMessage())));
                }
            }
        } else {
            clonedGit = Git.cloneRepository()
                    .setURI(normalizedRepoUrl)
                    .setDirectory(gitCacheDir)
                    .setCredentialsProvider(gitRemoteSupportService.credentials(username, password))
                    .setNoCheckout(true)
                    .call();
        }
        return clonedGit;
    }

    private List<Integer> changedNewLines(DiffFormatter formatter, DiffEntry entry) throws Exception {
        List<Integer> changedLines = new ArrayList<>();
        for (GitDiffVo.LineRange range : changedRanges(formatter, entry, false)) {
            for (int line = range.getStartLine(); line <= range.getEndLine(); line++) changedLines.add(line);
        }
        return changedLines;
    }

    private List<GitDiffVo.LineRange> changedRanges(DiffFormatter formatter, DiffEntry entry, boolean oldSide) throws Exception {
        if (oldSide && entry.getChangeType() == DiffEntry.ChangeType.ADD) return Collections.emptyList();
        if (!oldSide && entry.getChangeType() == DiffEntry.ChangeType.DELETE) return Collections.emptyList();
        List<GitDiffVo.LineRange> ranges = new ArrayList<>();
        FileHeader fileHeader = formatter.toFileHeader(entry);
        for (HunkHeader hunk : fileHeader.getHunks()) {
            for (Edit edit : hunk.toEditList()) {
                if (oldSide ? edit.getType() != Edit.Type.INSERT : edit.getType() != Edit.Type.DELETE) {
                    int begin = oldSide ? edit.getBeginA() : edit.getBeginB();
                    int end = oldSide ? edit.getEndA() : edit.getEndB();
                    if (end > begin) ranges.add(new GitDiffVo.LineRange(begin + 1, end));
                }
            }
        }
        return ranges;
    }

    private boolean isSupportedSourcePath(String path) {
        if (!StringUtils.hasText(path) || DiffEntry.DEV_NULL.equals(path)) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts")
                || lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js")
                || lower.endsWith(".jsx") || lower.endsWith(".vue") || lower.endsWith(".py")
                || lower.endsWith(".go") || lower.endsWith(".sql") || lower.endsWith(".xml")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".properties")
                || lower.endsWith(".json");
    }

    private String toClassName(String path) {
        String className = path;
        if (path.contains("src/main/java/")) {
            className = path.substring(path.indexOf("src/main/java/") + "src/main/java/".length());
        } else if (path.contains("src/test/java/")) {
            className = path.substring(path.indexOf("src/test/java/") + "src/test/java/".length());
        }
        return className.replace(".java", "").replace("/", ".");
    }

    private Set<String> buildFileCandidates(String filePath) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizePath(filePath));
        if (!filePath.contains("/") && filePath.contains(".")) {
            for (String pathLike : SourceClassUtil.buildSourcePathCandidates(filePath)) {
                candidates.add(normalizePath("src/main/java/" + pathLike));
                candidates.add(normalizePath("src/test/java/" + pathLike));
                candidates.add(normalizePath(pathLike));
            }
        } else if (filePath.endsWith(".java")) {
            if (filePath.startsWith("src/main/java/")) {
                candidates.add(normalizePath(filePath.substring("src/main/java/".length())));
            } else if (filePath.startsWith("src/test/java/")) {
                candidates.add(normalizePath(filePath.substring("src/test/java/".length())));
            }
        }
        candidates.removeIf(candidate -> !StringUtils.hasText(candidate));
        return candidates;
    }

    private String readExactPath(Repository repository, RevCommit revCommit, String candidate) throws Exception {
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(revCommit.getTree());
            treeWalk.setRecursive(true);
            treeWalk.setFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(candidate));
            if (treeWalk.next()) {
                ObjectId objectId = treeWalk.getObjectId(0);
                return readObject(repository, objectId);
            }
        }
        return null;
    }

    private String readPathSuffix(Repository repository, RevCommit revCommit, Set<String> candidates) throws Exception {
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(revCommit.getTree());
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                for (String candidate : candidates) {
                    if (path.equals(candidate) || path.endsWith("/" + candidate)) {
                        return readObject(repository, treeWalk.getObjectId(0));
                    }
                }
            }
        }
        return null;
    }

    private String readObject(Repository repository, ObjectId objectId) throws Exception {
        try (org.eclipse.jgit.lib.ObjectReader reader = repository.newObjectReader()) {
            org.eclipse.jgit.lib.ObjectLoader loader = reader.open(objectId);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
    }

    private File getGitCacheDir(String normalizedRepoUrl) {
        String repoHash = com.oAT.web.common.EncryptUtil.MD5(normalizedRepoUrl);
        return new File(resourceService.getGitCacheRoot(), repoHash);
    }

    private RepositoryLock getRepoLock(String repoUrl) {
        String lockKey = "oAT:lock:repo:" + com.oAT.web.common.EncryptUtil.MD5(repoUrl);
        return new RepositoryLock(lockKey);
    }

    /**
     * PostgreSQL advisory locks are connection-scoped, so they remain valid for
     * the full Git operation and are released even when the process exits.
     */
    private final class RepositoryLock {
        private final String key;
        private Connection connection;
        private ReentrantLock fallback;

        private RepositoryLock(String key) {
            this.key = key;
        }

        private void lock() {
            try {
                connection = dataSource.getConnection();
                try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")) {
                    statement.setString(1, key);
                    statement.execute();
                }
            } catch (SQLException exception) {
                closeConnection();
                fallback = fallbackRepoLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
                fallback.lock();
                logger.warn("event=git.repo_lock.local_fallback {}", LogFields.of(LogFields.map(
                        "repo_lock_hash", hash(key), "reason", exception.getMessage())));
            }
        }

        private void unlock() {
            if (connection != null) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
                    statement.setString(1, key);
                    statement.execute();
                } catch (SQLException exception) {
                    logger.warn("event=git.repo_lock.release_failed {}", LogFields.of(LogFields.map(
                            "repo_lock_hash", hash(key), "reason", exception.getMessage())));
                } finally {
                    closeConnection();
                }
            } else if (fallback != null) {
                fallback.unlock();
            }
        }

        private void closeConnection() {
            if (connection == null) return;
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The JDBC pool will discard a connection it cannot close cleanly.
            } finally {
                connection = null;
            }
        }
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').replaceAll("^/+", "");
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
