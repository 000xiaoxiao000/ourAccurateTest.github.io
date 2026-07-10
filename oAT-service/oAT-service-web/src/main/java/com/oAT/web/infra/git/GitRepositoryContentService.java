package com.oAT.web.infra.git;

import com.oAT.web.common.SourceClassUtil;
import com.oAT.web.exceptions.FriendlyException;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();

    @Autowired
    private GitRemoteSupportService gitRemoteSupportService;

    public List<GitDiffVo> getDiffDetail(String repoUrl, String username, String password, String oldCommit, String newCommit) {
        List<GitDiffVo> diffList = new ArrayList<>();
        if (!StringUtils.hasText(newCommit)) return diffList;

        String normalizedRepoUrl = gitRemoteSupportService.normalizeRemoteUrl(repoUrl);
        ReentrantLock lock = getRepoLock(normalizedRepoUrl);
        lock.lock();
        try {
            File gitCacheDir = getGitCacheDir(normalizedRepoUrl);
            Git clonedGit = openOrCloneBareWorktree(normalizedRepoUrl, username, password, gitCacheDir);
            try (Git git = clonedGit) {
                Repository repository = git.getRepository();
                ObjectId oldId = StringUtils.hasText(oldCommit) ? repository.resolve(oldCommit) : null;
                ObjectId newId = repository.resolve(newCommit);
                if (newId == null) {
                    logger.warn("Could not resolve new commit: {} in {}", newCommit, normalizedRepoUrl);
                    return diffList;
                }

                try (RevWalk walk = new RevWalk(repository)) {
                    RevCommit oldRev = oldId != null ? walk.parseCommit(oldId) : null;
                    RevCommit newRev = walk.parseCommit(newId);
                    try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                        formatter.setRepository(repository);
                        formatter.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
                        formatter.setDetectRenames(true);
                        List<DiffEntry> diffs = formatter.scan(oldRev == null ? null : oldRev.getTree(), newRev.getTree());
                        for (DiffEntry entry : diffs) {
                            String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
                            if (!path.endsWith(".java")) {
                                continue;
                            }
                            diffList.add(new GitDiffVo(toClassName(path), changedLines(formatter, entry), entry.getChangeType().name(), path));
                        }
                    }
                }
            }
            if (!gitCacheDir.setLastModified(System.currentTimeMillis())) {
                logger.debug("Failed to set last modified for {}", gitCacheDir);
            }
        } catch (Exception e) {
            String friendlyMessage = gitRemoteSupportService.friendlyError(e);
            logger.error("Failed to get git diff detail: {}", friendlyMessage, e);
            throw new FriendlyException(friendlyMessage, e);
        } finally {
            lock.unlock();
        }
        return diffList;
    }

    public String getFileContent(String repoUrl, String username, String password, String commitId, String filePath) {
        if (!StringUtils.hasText(commitId) || !StringUtils.hasText(filePath)) return null;
        String normalizedRepoUrl = gitRemoteSupportService.normalizeRemoteUrl(repoUrl);
        ReentrantLock lock = getRepoLock(normalizedRepoUrl);
        lock.lock();
        try {
            File gitCacheDir = getGitCacheDir(normalizedRepoUrl);
            Git clonedGit = openOrCloneBareWorktree(normalizedRepoUrl, username, password, gitCacheDir);
            try (Git git = clonedGit) {
                Repository repository = git.getRepository();
                ObjectId commitObj = repository.resolve(commitId);
                if (commitObj == null) {
                    logger.warn("Could not resolve commit {} in {}", commitId, normalizedRepoUrl);
                    return null;
                }
                try (RevWalk revWalk = new RevWalk(repository)) {
                    RevCommit revCommit = revWalk.parseCommit(commitObj);
                    Set<String> candidates = buildFileCandidates(filePath);
                    for (String candidate : candidates) {
                        String content = readExactPath(repository, revCommit, candidate);
                        if (content != null) {
                            return content;
                        }
                    }
                    return readPathSuffix(repository, revCommit, candidates);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get file content {}@{}: {}", filePath, commitId, e.getMessage());
        } finally {
            lock.unlock();
        }
        return null;
    }

    private Git openOrCloneBareWorktree(String normalizedRepoUrl, String username, String password, File gitCacheDir) throws Exception {
        Git clonedGit;
        if (new File(gitCacheDir, ".git").exists()) {
            clonedGit = Git.open(gitCacheDir);
            try {
                clonedGit.fetch().setCredentialsProvider(gitRemoteSupportService.credentials(username, password)).call();
            } catch (Exception e) {
                logger.warn("Fetch failed for cached git repository {}: {}", normalizedRepoUrl, e.getMessage());
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

    private List<Integer> changedLines(DiffFormatter formatter, DiffEntry entry) throws Exception {
        List<Integer> changedLines = new ArrayList<>();
        if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
            return changedLines;
        }
        FileHeader fileHeader = formatter.toFileHeader(entry);
        for (HunkHeader hunk : fileHeader.getHunks()) {
            for (Edit edit : hunk.toEditList()) {
                if (edit.getType() != Edit.Type.DELETE) {
                    for (int i = edit.getBeginB(); i < edit.getEndB(); i++) {
                        changedLines.add(i + 1);
                    }
                }
            }
        }
        return changedLines;
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

    private ReentrantLock getRepoLock(String repoUrl) {
        String lockKey = "oAT:lock:repo:" + com.oAT.web.common.EncryptUtil.MD5(repoUrl);
        return repoLocks.computeIfAbsent(lockKey, key -> new ReentrantLock());
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').replaceAll("^/+", "");
    }
}
