package com.oAT.web.api.version;

import com.oAT.web.service.AppService;
import com.oAT.web.service.GitService;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.VersionService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.GitCacheInfo;
import com.oAT.web.service.entity.GitPullEstimateVo;
import com.oAT.web.service.entity.PackageCommitVerifyVo;
import com.oAT.web.service.entity.VersionItemVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class VersionGitWorkflowService {
    private final AppService appService;
    private final GitService gitService;
    private final VersionService versionService;
    private final ResourceService resourceService;

    public VersionGitWorkflowService(AppService appService,
                                     GitService gitService,
                                     VersionService versionService,
                                     ResourceService resourceService) {
        this.appService = appService;
        this.gitService = gitService;
        this.versionService = versionService;
        this.resourceService = resourceService;
    }

    public GitPullEstimateVo checkGitPull(String appId,
                                          String branch,
                                          String commitId,
                                          String versionNumber,
                                          String excludePaths) {
        AppVo app = appService.getApp(appId);
        String finalBranch = branch != null ? branch.trim() : "";
        String finalCommitId = commitId != null ? commitId.trim() : "";

        validateExcludePaths(excludePaths);

        gitService.checkGitPull(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), finalBranch, finalCommitId);

        String checkCommitId = finalCommitId;
        if (checkCommitId.isEmpty()) {
            checkCommitId = gitService.getLatestCommitId(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), finalBranch);
        }

        assertVersionGitNotExists(appId, versionNumber, finalBranch, checkCommitId);

        GitPullEstimateVo estimate = gitService.estimateGitPull(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), finalBranch, checkCommitId, excludePaths);
        estimate.setPackageCommitVerify(verifyRuntimeCommit(appId, checkCommitId));
        return estimate;
    }

    public String startGitPull(String appId, String branch, String commitId, String excludePaths, String versionNumber) {
        AppVo app = appService.getApp(appId);
        String finalBranch = branch != null ? branch.trim() : "";
        String finalCommitId = StringUtils.hasText(commitId) ? commitId.trim() : null;

        validateExcludePaths(excludePaths);

        String checkCommitId = finalCommitId;
        if (checkCommitId == null) {
            checkCommitId = gitService.getLatestCommitId(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(), finalBranch);
        }

        assertVersionGitNotExists(appId, versionNumber, finalBranch, checkCommitId);
        assertGitCacheNotExists(finalBranch, checkCommitId, excludePaths);

        return gitService.startGitPullJob(app.getRepoAddress(), app.getRepoUserName(), app.getRepoPassword(),
                finalBranch, finalCommitId, excludePaths);
    }

    public PackageCommitVerifyVo verifyUploadedPackageCommit(String appId, String programFile, String commitId) throws Exception {
        String targetCommitId = StringUtils.hasText(commitId) ? commitId.trim() : readPackageCommitId(programFile);
        return verifyRuntimeCommit(appId, targetCommitId);
    }

    private void assertVersionGitNotExists(String appId, String versionNumber, String branch, String commitId) {
        if (!StringUtils.hasText(versionNumber)) {
            return;
        }
        VersionItemVo existing = versionService.getVersionByGitInfo(appId, versionNumber.trim(), branch, commitId);
        if (existing != null) {
            throw new IllegalArgumentException("该版本号下已存在相同的分支和 CommitID (版本号: " + existing.getVersionNumber() + ")");
        }
    }

    private void assertGitCacheNotExists(String branch, String commitId, String excludePaths) {
        GitCacheInfo existingCache = gitService.findExistingCache(branch, commitId, excludePaths);
        if (existingCache == null) {
            return;
        }
        String sizeText = formatFileSize(existingCache.getFileSizeBytes());
        String dateText = formatDateTime(existingCache.getCreateTime());
        throw new IllegalStateException(String.format(
                "该分支和 CommitID 的代码已在磁盘缓存中，请先删除旧文件或直接使用现有缓存创建版本 (缓存路径: %s, 大小: %s, 拉取时间: %s)",
                existingCache.getCachePath(), sizeText, dateText));
    }

    private void validateExcludePaths(String excludePaths) {
        if (!StringUtils.hasText(excludePaths)) {
            return;
        }

        String[] paths = excludePaths.split(",");
        for (String path : paths) {
            String trimmedPath = path.trim();
            if (!StringUtils.hasText(trimmedPath)) {
                continue;
            }
            if (trimmedPath.contains("..")) {
                throw new IllegalArgumentException("检测失败: 排除路径不能包含 ..");
            }
            if (trimmedPath.startsWith("/") || trimmedPath.startsWith("\\") || trimmedPath.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException("检测失败: 排除路径不能是绝对路径");
            }

            Path normalizedPath = Paths.get(trimmedPath).normalize();
            String normalized = normalizedPath.toString().replace('\\', '/');
            if (normalized.isEmpty() || ".".equals(normalized) || normalized.startsWith("../")) {
                throw new IllegalArgumentException("检测失败: 排除路径格式不合法");
            }
        }
    }

    private PackageCommitVerifyVo verifyRuntimeCommit(String appId, String targetCommitId) {
        return new PackageCommitVerifyVo(null, targetCommitId, null, false, "运行时 CommitId 校验已移除");
    }

    private String readPackageCommitId(String cachePath) throws Exception {
        if (!StringUtils.hasText(cachePath)) {
            throw new IllegalArgumentException("程序文件不能为空");
        }
        File cacheRoot = new File(resourceService.getCacheRoot()).getCanonicalFile();
        File packageFile = new File(cacheRoot, cachePath).getCanonicalFile();
        if (!packageFile.getPath().startsWith(cacheRoot.getPath() + File.separator) || !packageFile.exists() || !packageFile.isFile()) {
            throw new IllegalArgumentException("程序文件不存在");
        }
        try (ZipFile zipFile = new ZipFile(packageFile)) {
            ZipEntry entry = findBuildInfoEntry(zipFile);
            if (entry == null) {
                return null;
            }
            Properties props = new Properties();
            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                props.load(inputStream);
            }
            String commitId = props.getProperty("git.commit.id");
            if (!StringUtils.hasText(commitId)) {
                commitId = props.getProperty("git.commit.id.abbrev");
            }
            return commitId;
        }
    }

    private ZipEntry findBuildInfoEntry(ZipFile zipFile) {
        String[] buildInfoPaths = {
                "META-INF/git.properties",
                "META-INF/build-info.properties",
                "WEB-INF/classes/META-INF/git.properties",
                "WEB-INF/classes/META-INF/build-info.properties",
                "WEB-INF/classes/git.properties",
                "WEB-INF/classes/build-info.properties"
        };
        for (String buildInfoPath : buildInfoPaths) {
            ZipEntry entry = zipFile.getEntry(buildInfoPath);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private String normalizeCommitId(String commitId) {
        if (!StringUtils.hasText(commitId)) {
            return null;
        }
        String normalized = commitId.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("[0-9a-f]{8,40}").matcher(normalized);
        if (matcher.find()) {
            return matcher.group();
        }
        return normalized;
    }

    private String formatDateTime(java.util.Date date) {
        return date == null ? "-" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(date);
    }

    private String formatFileSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "-";
        }
        if (bytes >= 1024 * 1024) {
            return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
