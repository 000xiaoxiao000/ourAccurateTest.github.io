package com.oAT.web.infra.git;

import com.oAT.web.common.Job;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.entity.GitCacheInfo;
import com.oAT.web.service.entity.GitJobVo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class GitPullJobWorkerService {

    private static final Logger logger = LoggerFactory.getLogger(GitPullJobWorkerService.class);

    private final ResourceService resourceService;
    private final GitRemoteSupportService gitRemoteSupportService;

    public GitPullJobWorkerService(ResourceService resourceService,
                                   GitRemoteSupportService gitRemoteSupportService) {
        this.resourceService = resourceService;
        this.gitRemoteSupportService = gitRemoteSupportService;
    }

    public void downloadAndPackage(String repoUrl, String username, String password, String branch, String commitId, File targetZipFile) {
        Path tempDir = null;
        String normalizedRepoUrl = gitRemoteSupportService.normalizeRemoteUrl(repoUrl);
        try {
            tempDir = Files.createTempDirectory("oAT_git_clone");
            try (Git git = Git.cloneRepository()
                    .setURI(normalizedRepoUrl)
                    .setDirectory(tempDir.toFile())
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(StringUtils.hasText(username) ? username : "git", password))
                    .setBranch(branch)
                    .call()) {
                if (StringUtils.hasText(commitId)) {
                    git.checkout().setName(commitId).call();
                }
            }
            deleteFile(new File(tempDir.toFile(), ".git"));
            zipDirectory(tempDir.toFile(), targetZipFile);
        } catch (Exception e) {
            logger.error("Git download failed", e);
            throw new RuntimeException("代码拉取打包失败: " + gitRemoteSupportService.friendlyError(e));
        } finally {
            if (tempDir != null) {
                deleteFile(tempDir.toFile());
            }
        }
    }

    public void runPullJob(String taskKey,
                           Job<GitJobVo> job,
                           String normalizedRepoUrl,
                           String username,
                           String password,
                           String branch,
                           String commitId,
                           String excludePaths,
                           Map<String, String> runningTaskMap) {
        GitJobVo gitJobVo = job.getData();
        job.state = Job.JobState.active;
        long pullStartTime = System.currentTimeMillis();
        File tempZip = null;
        Path tempDir = null;
        try {
            job.getProgress().next("正在拉取代码...", 70);
            job.getProgress().total = 100;
            tempDir = Files.createTempDirectory("oAT_git_clone");
            File cloneDir = tempDir.toFile();

            try (Git git = Git.cloneRepository()
                    .setURI(normalizedRepoUrl)
                    .setDirectory(cloneDir)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(StringUtils.hasText(username) ? username : "git", password))
                    .setBranch(branch)
                    .setProgressMonitor(progressMonitor(job))
                    .call()) {
                if (StringUtils.hasText(commitId)) {
                    job.getProgress().next("切换 Commit...", 5);
                    git.checkout().setName(commitId).call();
                }
                gitJobVo.setRepoCommitId(git.getRepository().resolve("HEAD").getName());
            }

            applyExcludePaths(cloneDir, excludePaths);
            deleteFile(new File(cloneDir, ".git"));

            job.getProgress().next("正在打包...", 20);
            tempZip = File.createTempFile("oat_git_", ".zip");
            zipDirectory(cloneDir, tempZip);

            job.getProgress().next("处理文件...", 10);
            String md5 = md5(tempZip.toPath());
            String fileName = "git-" + branch + "-" + (StringUtils.hasText(gitJobVo.getRepoCommitId()) ? gitJobVo.getRepoCommitId() : "head") + ".zip";
            File targetFile = resourceService.createCacheFile(md5, fileName);
            if (!targetFile.exists() || targetFile.length() != tempZip.length()) {
                Files.copy(tempZip.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            gitJobVo.setMd5(md5);
            gitJobVo.setFileName(fileName);
            gitJobVo.setCachePath(resourceService.getCachePath(md5, fileName));
            gitJobVo.setPackageSizeBytes(targetFile.length());
            gitJobVo.setPullDurationMs(System.currentTimeMillis() - pullStartTime);
            gitJobVo.setSuccess(true);
            gitJobVo.setFinish(true);
            job.getProgress().finish("完成");
            job.state = Job.JobState.finish;
        } catch (Exception e) {
            logger.error("Git Job Failed", e);
            job.state = Job.JobState.error;
            gitJobVo.setSuccess(false);
            gitJobVo.setMessage(gitRemoteSupportService.friendlyError(e));
            gitJobVo.setFinish(true);
            runningTaskMap.remove(taskKey);
        } finally {
            if (tempZip != null && !tempZip.delete()) {
                logger.warn("Failed to delete temp zip: {}", tempZip.getAbsolutePath());
            }
            if (tempDir != null) {
                deleteFile(tempDir.toFile());
            }
        }
    }

    public void deleteCacheFile(String cachePath) {
        if (!StringUtils.hasText(cachePath)) {
            return;
        }
        File cacheRoot = new File(resourceService.getCacheRoot());
        File file = new File(cacheRoot, cachePath);
        if (!file.exists()) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.equals(cacheRoot)) {
            deleteFile(parent);
            logger.info("Deleted cache directory: {}", parent.getAbsolutePath());
            deleteEmptyParents(parent.getParentFile(), cacheRoot);
        } else if (file.delete()) {
            logger.info("Deleted cache file: {}", file.getAbsolutePath());
        }
    }

    public GitCacheInfo findExistingCache(String branch, String commitId) {
        if (!StringUtils.hasText(branch) || !StringUtils.hasText(commitId)) {
            return null;
        }
        String normalizedBranch = gitRemoteSupportService.normalizeBranchName(branch);
        String normalizedCommitId = commitId.trim();
        File rootDir = new File(resourceService.getCacheRoot());
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            return null;
        }

        String expectedPrefix = "git-" + normalizedBranch + "-" + normalizedCommitId;
        File[] subdirs = rootDir.listFiles(File::isDirectory);
        if (subdirs == null) {
            return null;
        }
        for (File subdir : subdirs) {
            File[] files = subdir.listFiles((dir, name) -> name.startsWith(expectedPrefix) && name.endsWith(".zip"));
            if (files != null && files.length > 0) {
                File found = files[0];
                String relativePath = subdir.getName() + "/" + found.getName();
                logger.info("Found existing cache for branch={}, commitId={}: {}", normalizedBranch, normalizedCommitId, relativePath);
                return new GitCacheInfo(relativePath, found.length(), new java.util.Date(found.lastModified()));
            }
        }
        return null;
    }

    private void applyExcludePaths(File cloneDir, String excludePaths) {
        if (!StringUtils.hasText(excludePaths)) {
            return;
        }
        String[] paths = excludePaths.split(",");
        for (String path : paths) {
            String trimmedPath = path.trim();
            if (trimmedPath.isEmpty()) {
                continue;
            }
            Path sanitizedPath;
            try {
                sanitizedPath = Paths.get(trimmedPath).normalize();
            } catch (InvalidPathException ex) {
                throw new RuntimeException("排除路径格式不合法: " + trimmedPath);
            }
            if (sanitizedPath.isAbsolute() || sanitizedPath.startsWith("..")) {
                throw new RuntimeException("排除路径格式不合法: " + trimmedPath);
            }
            File toDelete = new File(cloneDir, sanitizedPath.toString());
            if (toDelete.exists()) {
                deleteFile(toDelete);
            }
        }
    }

    private ProgressMonitor progressMonitor(Job<GitJobVo> job) {
        return new ProgressMonitor() {
            int totalWork = 0;
            int workDone = 0;

            @Override
            public void start(int totalTasks) {
            }

            @Override
            public void beginTask(String title, int totalWork) {
                this.totalWork = totalWork;
                this.workDone = 0;
                job.getProgress().updateName(toChineseProgressName(title));
            }

            @Override
            public void update(int completed) {
                workDone += completed;
                if (totalWork > 0) {
                    job.getProgress().loaded = (int) ((float) workDone / totalWork * 100);
                }
            }

            @Override
            public void endTask() {
                job.getProgress().loaded = 100;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    private void zipDirectory(File sourceDir, File zipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos);
             java.util.stream.Stream<Path> stream = Files.walk(sourceDir.toPath())) {
            Path sourcePath = sourceDir.toPath();
            stream.filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        ZipEntry zipEntry = new ZipEntry(sourcePath.relativize(path).toString());
                        try {
                            zos.putNextEntry(zipEntry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private void deleteFile(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteFile(f);
                }
            }
        }
        if (!file.delete()) {
            logger.warn("Failed to delete file: {}", file.getAbsolutePath());
        }
    }

    private void deleteEmptyParents(File directory, File root) {
        File current = directory;
        while (current != null && current.exists() && current.isDirectory() && !current.equals(root)) {
            File[] files = current.listFiles();
            if (files != null && files.length > 0) {
                break;
            }
            File parent = current.getParentFile();
            if (current.delete()) {
                logger.info("Deleted empty parent directory: {}", current.getAbsolutePath());
                current = parent;
            } else {
                logger.warn("Failed to delete empty parent directory: {}", current.getAbsolutePath());
                break;
            }
        }
    }

    private String md5(Path path) throws Exception {
        try (InputStream fis = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            MessageDigest complete = MessageDigest.getInstance("MD5");
            int numRead;
            while ((numRead = fis.read(buffer)) != -1) {
                complete.update(buffer, 0, numRead);
            }
            byte[] data = complete.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : data) {
                sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        }
    }

    private String toChineseProgressName(String gitPhase) {
        if (!StringUtils.hasText(gitPhase)) {
            return "正在处理...";
        }
        String lower = gitPhase.toLowerCase().trim();
        if (lower.startsWith("remote:") || lower.contains("remote")) return "连接远程仓库";
        if (lower.contains("counting objects")) return "统计对象";
        if (lower.contains("compressing objects")) return "压缩对象";
        if (lower.contains("receiving objects")) return "接收对象";
        if (lower.contains("resolving deltas")) return "解析增量";
        if (lower.contains("checking out")) return "检出文件";
        if (lower.contains("updating references")) return "更新引用";
        if (lower.contains("clone") || lower.contains("cloning")) return "克隆仓库";
        if (lower.contains("fetch")) return "拉取远程数据";
        return gitPhase;
    }
}
