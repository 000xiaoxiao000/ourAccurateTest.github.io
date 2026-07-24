package com.oAT.web.language.java;

import com.oAT.web.common.SourceClassUtil;
import com.oAT.web.logging.LogFields;
import com.oAT.web.persistence.VersionCenterRepository;
import com.oAT.web.persistence.entity.StaticSourceInfo;
import com.oAT.web.persistence.entity.VersionCenterIndex;
import com.oAT.web.persistence.entity.VersionItem;
import com.oAT.web.service.GitService;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.entity.AppVo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class JavaSourcePresenceService {
    private static final Logger logger = LoggerFactory.getLogger(JavaSourcePresenceService.class);

    private final Cache<String, List<String>> zipEntryCache = Caffeine.newBuilder()
            .maximumWeight(64L * 1024 * 1024)
            .weigher((String key, List<String> entries) -> Math.max(1, entries.stream()
                    .mapToInt(String::length).sum()))
            .expireAfterAccess(Duration.ofHours(2))
            .recordStats()
            .build();
    private final VersionCenterRepository versionCenterRepository;
    private final ResourceService resourceService;
    private final GitService gitService;

    public JavaSourcePresenceService(VersionCenterRepository versionCenterRepository,
                                     ResourceService resourceService,
                                     GitService gitService) {
        this.versionCenterRepository = versionCenterRepository;
        this.resourceService = resourceService;
        this.gitService = gitService;
    }

    public void clearCache() {
        zipEntryCache.invalidateAll();
    }

    public SourcePresenceFilterResult filterExistingClasses(AppVo app,
                                                            String commitId,
                                                            List<StaticSourceInfo> staticInfos) {
        if (app == null || !StringUtils.hasText(commitId) || staticInfos == null || staticInfos.isEmpty()) {
            return SourcePresenceFilterResult.unchanged(staticInfos, null);
        }

        List<String> entryNames = getZipEntryNames(app.getId(), commitId);
        Set<String> entrySet = entryNames == null ? null : new HashSet<>(entryNames);
        List<StaticSourceInfo> filtered = new ArrayList<>();
        List<String> missingClassNames = new ArrayList<>();

        for (StaticSourceInfo staticInfo : staticInfos) {
            if (staticInfo == null || staticInfo.getClassInfo() == null) {
                continue;
            }
            String className = staticInfo.getClassInfo().getClassName();
            boolean exists = entrySet != null
                    ? hasSourceEntry(entrySet, className)
                    : hasGitSource(app, commitId, className);
            if (exists) {
                filtered.add(staticInfo);
            } else if (StringUtils.hasText(className)) {
                missingClassNames.add(className);
            }
        }

        String sourceHint = entrySet != null ? "本地源码包" : "Git 仓库";
        if (filtered.isEmpty()) {
            return SourcePresenceFilterResult.unchanged(staticInfos, sourceHint);
        }
        return new SourcePresenceFilterResult(filtered, missingClassNames, sourceHint, true);
    }

    private boolean hasGitSource(AppVo app, String commitId, String className) {
        String content = gitService.getFileContent(
                app.getRepoAddress(),
                app.getRepoUserName(),
                app.getRepoPassword(),
                commitId,
                className);
        return content != null;
    }

    private boolean hasSourceEntry(Set<String> entrySet, String className) {
        if (entrySet == null || entrySet.isEmpty()) {
            return false;
        }
        for (String sourcePath : SourceClassUtil.buildSourcePathCandidates(className)) {
            for (String entryName : entrySet) {
                String normalizedName = entryName.replace('\\', '/');
                if (normalizedName.endsWith("/" + sourcePath) || normalizedName.equals(sourcePath)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> getZipEntryNames(String appId, String commitId) {
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(commitId)) {
            return null;
        }

        List<VersionCenterIndex> versions = versionCenterRepository.findTop1ByVersionItem_AppIdAndVersionItem_RepoCommitId(appId, commitId);
        if (versions == null || versions.isEmpty()) {
            return null;
        }

        VersionItem item = versions.get(0).getVersionItem();
        if (item == null || !StringUtils.hasText(item.getProgramFile())) {
            return null;
        }

        File codeFile = new File(item.getProgramFile());
        if (!codeFile.isAbsolute()) {
            codeFile = new File(resourceService.getCacheRoot(), item.getProgramFile());
        }
        if (!codeFile.exists()) {
            return null;
        }

        String sourcePath = codeFile.getAbsolutePath();
        String cacheKey = sourcePath + ':' + codeFile.length() + ':' + codeFile.lastModified();
        return zipEntryCache.get(cacheKey, ignored -> readZipEntries(sourcePath));
    }

    private List<String> readZipEntries(String path) {
        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(new File(path))) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                entries.add(en.nextElement().getName());
            }
        } catch (IOException e) {
            logger.error("event=java_source_presence.read_zip_entries.failed {}", LogFields.of(LogFields.map(
                    "path_hash", hash(path),
                    "reason", e.getMessage())), e);
        }
        return entries;
    }

    private String hash(String value) {
        return Integer.toHexString(String.valueOf(value).hashCode());
    }

    public static class SourcePresenceFilterResult {
        private final List<StaticSourceInfo> staticInfos;
        private final List<String> missingClassNames;
        private final String sourceHint;
        private final boolean filtered;

        public SourcePresenceFilterResult(List<StaticSourceInfo> staticInfos,
                                          List<String> missingClassNames,
                                          String sourceHint,
                                          boolean filtered) {
            this.staticInfos = staticInfos == null ? Collections.emptyList() : staticInfos;
            this.missingClassNames = missingClassNames == null ? Collections.emptyList() : missingClassNames;
            this.sourceHint = sourceHint;
            this.filtered = filtered;
        }

        public static SourcePresenceFilterResult unchanged(List<StaticSourceInfo> staticInfos, String sourceHint) {
            return new SourcePresenceFilterResult(staticInfos, Collections.emptyList(), sourceHint, false);
        }

        public List<StaticSourceInfo> getStaticInfos() { return staticInfos; }
        public List<String> getMissingClassNames() { return missingClassNames; }
        public String getSourceHint() { return sourceHint; }
        public boolean isFiltered() { return filtered; }
    }
}
