package com.oAT.web.domain.version;

import com.oAT.web.common.Job;
import com.oAT.web.common.compare.CompareResult;
import com.oAT.web.language.java.JavaSourceMethodExtractionService;
import com.oAT.web.language.java.JavaSourceMethodExtractionService.MethodInfo;
import com.oAT.web.service.GitService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.CompareJobVo;
import com.oAT.web.service.entity.GitDiffVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VersionGitDiffCompareService {

    @Autowired
    private GitService gitService;

    @Autowired
    private JavaSourceMethodExtractionService javaSourceMethodExtractionService;

    public List<CompareResult> buildGitDifferences(Job<CompareJobVo> job, AppVo appInfo,
                                                   String packageName, String oldCommit, String newCommit) {
        List<GitDiffVo> diffs = gitService.getDiffDetail(appInfo.getRepoAddress(), appInfo.getRepoUserName(),
                appInfo.getRepoPassword(), oldCommit, newCommit);

        List<CompareResult> differences = new ArrayList<>();
        int totalClasses = 0;
        int addedClasses = 0;
        int deletedClasses = 0;
        int modifiedClasses = 0;
        int addedMethods = 0;
        int deletedMethods = 0;
        int modifiedMethods = 0;

        if (diffs != null) {
            String packageFilter = normalizePackageFilter(packageName);
            for (GitDiffVo diffVo : diffs) {
                String changeType = diffVo.getChangeType();
                String dottedName = diffVo.getClassName();
                dottedName = dottedName.replaceFirst("^[./]+", "");
                if (!matchesPackageFilter(dottedName, packageFilter)) {
                    continue;
                }

                String filePath = dottedName.replace('.', '/') + ".java";
                CompareResult.Model classModel;
                if ("ADD".equals(changeType)) {
                    classModel = CompareResult.Model.add;
                } else if ("DELETE".equals(changeType)) {
                    classModel = CompareResult.Model.delete;
                } else {
                    classModel = CompareResult.Model.update;
                }

                CompareResult result = new CompareResult(dottedName, classModel);
                totalClasses++;
                if (classModel == CompareResult.Model.add) {
                    addedClasses++;
                } else if (classModel == CompareResult.Model.delete) {
                    deletedClasses++;
                } else {
                    modifiedClasses++;
                }

                MethodStats methodStats = fillClassMethodDifferences(job, appInfo, oldCommit, newCommit,
                        diffVo, dottedName, filePath, classModel, result);
                addedMethods += methodStats.added;
                deletedMethods += methodStats.deleted;
                modifiedMethods += methodStats.modified;
                differences.add(result);
            }
        }

        job.getLogger().info(String.format("比对完成: 共分析 %d 个类 (新增:%d, 修改:%d, 删除:%d)",
                totalClasses, addedClasses, modifiedClasses, deletedClasses));
        job.getLogger().info(String.format("方法变更统计: 新增:%d, 修改:%d, 删除:%d",
                addedMethods, modifiedMethods, deletedMethods));
        return differences;
    }

    private MethodStats fillClassMethodDifferences(Job<CompareJobVo> job, AppVo appInfo,
                                                   String oldCommit, String newCommit, GitDiffVo diffVo,
                                                   String dottedName, String filePath,
                                                   CompareResult.Model classModel, CompareResult result) {
        MethodStats stats = new MethodStats();
        try {
            SourcePair sourcePair = loadSourcePair(appInfo, oldCommit, newCommit, diffVo, filePath, classModel);
            List<Integer> changedLines = diffVo.getChangedLines();
            Map<String, MethodInfo> oldMethods = sourcePair.oldContent == null
                    ? Collections.emptyMap()
                    : javaSourceMethodExtractionService.extractMethodsWithLines(sourcePair.oldContent);
            Map<String, MethodInfo> newMethods = sourcePair.newContent == null
                    ? Collections.emptyMap()
                    : javaSourceMethodExtractionService.extractMethodsWithLines(sourcePair.newContent);

            if (classModel == CompareResult.Model.delete) {
                job.getLogger().info(String.format("发现【删除】类: %s", dottedName));
                for (Map.Entry<String, MethodInfo> method : oldMethods.entrySet()) {
                    MethodInfo info = method.getValue();
                    result.add(method.getKey(), String.format("行: %d-%d", info.startLine, info.endLine), CompareResult.Model.delete);
                    stats.deleted++;
                }
            } else if (classModel == CompareResult.Model.add) {
                job.getLogger().info(String.format("发现【新增】类: %s", dottedName));
                for (Map.Entry<String, MethodInfo> method : newMethods.entrySet()) {
                    MethodInfo info = method.getValue();
                    result.add(method.getKey(), String.format("行: %d-%d", info.startLine, info.endLine), CompareResult.Model.add);
                    stats.added++;
                }
            } else {
                fillUpdatedClassDifferences(job, dottedName, changedLines, oldMethods, newMethods, result, stats);
            }
        } catch (Exception e) {
            job.getLogger().error("处理类 " + dottedName + " 差异失败: " + e.getMessage());
        }
        return stats;
    }

    private SourcePair loadSourcePair(AppVo appInfo, String oldCommit, String newCommit, GitDiffVo diffVo,
                                      String filePath, CompareResult.Model classModel) {
        String repo = appInfo.getRepoAddress();
        String user = appInfo.getRepoUserName();
        String pass = appInfo.getRepoPassword();
        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(diffVo.getOriginalPath())) {
            candidates.add(diffVo.getOriginalPath());
        }
        candidates.add("src/main/java/" + filePath);
        candidates.add("src/test/java/" + filePath);
        candidates.add(filePath);

        for (String candidate : candidates) {
            String oldContent = null;
            if (classModel != CompareResult.Model.add && StringUtils.hasText(oldCommit)) {
                oldContent = gitService.getFileContent(repo, user, pass, oldCommit, candidate);
            }
            String newContent = null;
            if (classModel != CompareResult.Model.delete) {
                newContent = gitService.getFileContent(repo, user, pass, newCommit, candidate);
            }

            if ((classModel == CompareResult.Model.add && newContent != null)
                    || (classModel == CompareResult.Model.delete && oldContent != null)
                    || (classModel == CompareResult.Model.update && (oldContent != null || newContent != null))) {
                return new SourcePair(oldContent, newContent);
            }
        }
        return new SourcePair(null, null);
    }

    private void fillUpdatedClassDifferences(Job<CompareJobVo> job, String dottedName, List<Integer> changedLines,
                                             Map<String, MethodInfo> oldMethods, Map<String, MethodInfo> newMethods,
                                             CompareResult result, MethodStats stats) {
        boolean nameLogged = false;
        Set<String> retainedMethodNames = new LinkedHashSet<>();
        for (Map.Entry<String, MethodInfo> method : oldMethods.entrySet()) {
            String methodName = method.getKey();
            MethodInfo oldInfo = method.getValue();
            if (newMethods.containsKey(methodName)) {
                MethodInfo newInfo = newMethods.get(methodName);
                boolean bodyChanged = !Objects.equals(oldInfo.body, newInfo.body);
                boolean linesIntersect = changedLines.stream().anyMatch(line -> line >= newInfo.startLine && line <= newInfo.endLine);
                if (bodyChanged || linesIntersect) {
                    if (!nameLogged) {
                        job.getLogger().info(String.format("发现【修改】类: %s", dottedName));
                        nameLogged = true;
                    }
                    result.add(methodName, String.format("行: %d-%d", newInfo.startLine, newInfo.endLine), CompareResult.Model.update);
                    retainedMethodNames.add(methodName);
                    stats.modified++;
                    job.getLogger().info(String.format("    - 变更方法: %s (行: %d-%d)", methodName, newInfo.startLine, newInfo.endLine));
                }
                newMethods.remove(methodName);
            } else {
                if (!nameLogged) {
                    job.getLogger().info(String.format("发现【修改】类: %s", dottedName));
                    nameLogged = true;
                }
                result.add(methodName, String.format("行: %d-%d", oldInfo.startLine, oldInfo.endLine), CompareResult.Model.delete);
                retainedMethodNames.add(methodName);
                stats.deleted++;
                job.getLogger().info(String.format("    - 删除方法: %s", methodName));
            }
        }
        for (Map.Entry<String, MethodInfo> newMethod : newMethods.entrySet()) {
            if (!nameLogged) {
                job.getLogger().info(String.format("发现【修改】类: %s", dottedName));
                nameLogged = true;
            }
            MethodInfo info = newMethod.getValue();
            result.add(newMethod.getKey(), String.format("行: %d-%d", info.startLine, info.endLine), CompareResult.Model.add);
            stats.added++;
            job.getLogger().info(String.format("    - 新增方法: %s (行: %d-%d)", newMethod.getKey(), info.startLine, info.endLine));
        }
        if (!nameLogged) {
            job.getLogger().info(String.format("发现【修改】类(细节无显著变化): %s", dottedName));
        } else {
            keepOnlyDeclaredClassMethods(result, dottedName, retainedMethodNames);
        }
    }

    private void keepOnlyDeclaredClassMethods(CompareResult compareResult, String dottedName, Set<String> retainedMethodNames) {
        if (compareResult == null || retainedMethodNames == null || retainedMethodNames.isEmpty()) {
            return;
        }
        String simpleClassName = dottedName.contains(".") ? dottedName.substring(dottedName.lastIndexOf('.') + 1) : dottedName;
        String nestedPrefix = simpleClassName + "$";
        CompareResult.Method[] methods = compareResult.getMethods();
        if (methods == null || methods.length == 0) {
            return;
        }
        LinkedHashSet<String> normalizedRetained = retainedMethodNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        compareResult.removeMethodsIf(method -> {
            if (method == null || !StringUtils.hasText(method.getName())) {
                return false;
            }
            String trimmed = method.getName().trim();
            if (normalizedRetained.contains(trimmed)) {
                return false;
            }
            if (trimmed.startsWith(simpleClassName + ".")) {
                return false;
            }
            return trimmed.startsWith(nestedPrefix);
        });
    }

    private String normalizePackageFilter(String packageName) {
        if (!StringUtils.hasText(packageName)) {
            return null;
        }
        String normalized = packageName.trim();
        if ("*".equals(normalized)) {
            return null;
        }
        if (normalized.endsWith(".*")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized;
    }

    private boolean matchesPackageFilter(String className, String packageFilter) {
        if (!StringUtils.hasText(className) || !StringUtils.hasText(packageFilter)) {
            return true;
        }
        return className.equals(packageFilter) || className.startsWith(packageFilter + ".");
    }

    private static final class SourcePair {
        private final String oldContent;
        private final String newContent;

        private SourcePair(String oldContent, String newContent) {
            this.oldContent = oldContent;
            this.newContent = newContent;
        }
    }

    private static final class MethodStats {
        private int added;
        private int deleted;
        private int modified;
    }
}
