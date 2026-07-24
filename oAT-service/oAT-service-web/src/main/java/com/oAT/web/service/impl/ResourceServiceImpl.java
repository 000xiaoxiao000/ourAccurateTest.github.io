package com.oAT.web.service.impl;

import com.oAT.web.logging.LogFields;
import com.oAT.web.service.ResourceService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

@Service
public class ResourceServiceImpl implements ResourceService, InitializingBean{

    private static final Logger logger = LoggerFactory.getLogger(ResourceServiceImpl.class);

    @org.springframework.beans.factory.annotation.Value("${oat.data.path:${user.home}/.oAT/cache/}")
    private String cacheRoot;

    @Override
    public File createCacheFile(String md5, String fileName) {
        String path = cacheRoot + (cacheRoot.endsWith("/") ? "" : "/") + buildDirectoryByMd5(md5) + "/" + fileName;
        File f = new File(path);
        if (!f.getParentFile().exists()) {
            boolean created = f.getParentFile().mkdirs();
            if (!created && !f.getParentFile().exists()) {
                throw new RuntimeException("缓存目录创建失败：" + f.getParentFile().getAbsolutePath());
            }
        }
        if (!f.exists()) {
            try {
                f.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("缓存文件创建失败：" + f.toString(), e);
            }
        } else if (f.isDirectory()) {
            throw new RuntimeException("缓存文件创建失败！存在同名目录：" + f.toString());
        }
        return f;
    }

    @Override
    public String getCachePath(String md5, String fileName) {
        return buildDirectoryByMd5(md5) + "/" + fileName;
    }


    // 基于md5 获取文件的存储目录
    private String buildDirectoryByMd5(String md5) {
        return md5.substring(0, 16);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        //版本比对缓存路径
        if (cacheRoot == null) {
             cacheRoot = System.getProperty("user.home") + "/.oAT/cache/";
        }
        checkAndCleanDiskSpace(); // 初始化时执行一次检查
    }

    @Override
    public String getCacheRoot() {
        return cacheRoot;
    }

    @Override
    public String getGitCacheRoot() {
        File root = new File(cacheRoot, "git-cache");
        if (!root.exists()) {
            root.mkdirs();
        }
        return root.getAbsolutePath();
    }

    @Override
    @Scheduled(cron = "0 0 3 * * ?") // 每天凌晨3点执行
    public void checkAndCleanDiskSpace() {
        checkDiskSpaceThreshold(0.15); // 常规清理，维持 15% 剩余
        cleanOldGitCache(7); // 清理 7 天未使用的 Git 缓存
    }

    private void checkDiskSpaceThreshold(double threshold) {
        File dataDir = new File(cacheRoot);
        if (!dataDir.exists()) return;

        long totalSpace = dataDir.getTotalSpace();
        long usableSpace = dataDir.getUsableSpace();

        if (totalSpace > 0) {
            double freeRatio = (double) usableSpace / totalSpace;
            if (freeRatio < threshold) {
                logger.warn("event=storage.disk_space.low {}", LogFields.of(LogFields.map(
                        "free_ratio_percent", String.format("%.2f", freeRatio * 100),
                        "threshold_percent", String.format("%.2f", threshold * 100),
                        "usable_bytes", usableSpace,
                        "total_bytes", totalSpace,
                        "cache_root_hash", hash(cacheRoot))));
                // 紧急清理：删除旧的 git 缓存和下载的 zip 缓存
                forceCleanCache(totalSpace, threshold);
            }
        }
    }

    private void cleanOldGitCache(int days) {
        File gitCacheRoot = new File(getGitCacheRoot());
        if (!gitCacheRoot.exists()) return;

        long cutoff = System.currentTimeMillis() - (long) days * 24 * 3600 * 1000;
        File[] repos = gitCacheRoot.listFiles();
        if (repos != null) {
            for (File repo : repos) {
                if (repo.isDirectory() && repo.lastModified() < cutoff) {
                    logger.info("event=storage.git_cache.cleanup_old {}", LogFields.of(LogFields.map(
                            "directory_hash", hash(repo.getAbsolutePath()),
                            "retention_days", days)));
                    deleteDirectory(repo);
                }
            }
        }
    }

    private void forceCleanCache(long totalSpace, double targetFreeRatio) {
        File gitCacheRoot = new File(getGitCacheRoot());
        File[] repos = gitCacheRoot.listFiles();
        if (repos == null) return;

        // 按最后修改时间排序（最早的先删除）
        Arrays.sort(repos, Comparator.comparingLong(File::lastModified));

        for (File repo : repos) {
            long currentUsable = new File(cacheRoot).getUsableSpace();
            if ((double) currentUsable / totalSpace >= targetFreeRatio + 0.05) { // 加 5% 缓冲
                break;
            }
            logger.warn("event=storage.git_cache.force_cleanup {}", LogFields.of(LogFields.map(
                    "directory_hash", hash(repo.getAbsolutePath()),
                    "target_free_ratio", targetFreeRatio)));
            deleteDirectory(repo);
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) deleteDirectory(f);
                    else f.delete();
                }
            }
            dir.delete();
        }
    }

    private String hash(String value) {
        return Integer.toHexString(String.valueOf(value).hashCode());
    }
}
