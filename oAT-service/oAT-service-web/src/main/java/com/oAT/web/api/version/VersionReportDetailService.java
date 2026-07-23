package com.oAT.web.api.version;

import com.oAT.web.api.version.VersionApiPayloads.AppSummary;
import com.oAT.web.api.version.VersionApiPayloads.CompareReportDetailSummary;
import com.oAT.web.api.version.VersionApiPayloads.DifferenceGroupSummary;
import com.oAT.web.api.version.VersionApiPayloads.EndpointImpactSummary;
import com.oAT.web.api.version.VersionApiPayloads.EndpointLinkedUsecaseSummary;
import com.oAT.web.api.version.VersionApiPayloads.MethodDifferenceSummary;
import com.oAT.web.api.version.VersionApiPayloads.UsecaseImpactSummary;
import com.oAT.web.api.version.VersionApiPayloads.VersionReportDetailPayload;
import com.oAT.web.persistence.entity.VersionCompareReport;
import com.oAT.web.service.ApiEndpointAnalysisService;
import com.oAT.web.service.AppService;
import com.oAT.web.service.UsecaseService;
import com.oAT.web.service.entity.ApiEndpointViewVo;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.CompareJobVo;
import com.oAT.web.service.entity.UsecaseDirectoryVo;
import com.oAT.web.service.entity.UsecaseVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class VersionReportDetailService {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final AppService appService;
    private final UsecaseService usecaseService;
    private final ApiEndpointAnalysisService apiEndpointAnalysisService;

    public VersionReportDetailService(AppService appService,
                                      UsecaseService usecaseService,
                                      ApiEndpointAnalysisService apiEndpointAnalysisService) {
        this.appService = appService;
        this.usecaseService = usecaseService;
        this.apiEndpointAnalysisService = apiEndpointAnalysisService;
    }

    public VersionReportDetailPayload buildReadyPayload(VersionCompareReport report) {
        VersionReportDetailPayload payload = new VersionReportDetailPayload();
        payload.setState("ready");
        payload.setApp(toAppSummary(appService.getApp(report.getAppId())));
        payload.setReport(toCompareReportDetailSummary(report));
        payload.setDifferences(buildDifferenceGroups(report));
        payload.setUsecases(buildUsecaseImpacts(report));
        payload.setEndpoints(buildEndpointImpacts(report));
        return payload;
    }

    public VersionReportDetailPayload buildPendingPayload(CompareJobVo compareJob) {
        VersionReportDetailPayload payload = new VersionReportDetailPayload();
        payload.setState("pending");
        payload.setRetryMessage("比对报告正在生成或索引刷新中，页面会自动重试。");
        payload.setApp(compareJob == null ? null : toAppSummary(appService.getApp(compareJob.getAppId())));
        return payload;
    }

    private List<DifferenceGroupSummary> buildDifferenceGroups(VersionCompareReport report) {
        Map<String, DifferenceGroupSummary> groups = new LinkedHashMap<>();
        VersionCompareReport.Difference[] diffs = report.getDifferences();
        if (diffs == null) {
            return new ArrayList<>();
        }
        for (VersionCompareReport.Difference difference : diffs) {
            if (difference == null) {
                continue;
            }
            if ("class".equals(difference.getType())) {
                DifferenceGroupSummary group = groups.computeIfAbsent(difference.getValue(), className -> {
                    DifferenceGroupSummary item = new DifferenceGroupSummary();
                    item.setClassName(className);
                    item.setModel(difference.getModel());
                    item.setMethods(new ArrayList<>());
                    return item;
                });
                if (!StringUtils.hasText(group.getModel())) {
                    group.setModel(difference.getModel());
                }
            } else if ("method".equals(difference.getType())) {
                MethodDifferenceSummary method = parseMethodDifference(difference);
                if (method == null) {
                    continue;
                }
                DifferenceGroupSummary group = groups.computeIfAbsent(method.getClassName(), className -> {
                    DifferenceGroupSummary item = new DifferenceGroupSummary();
                    item.setClassName(className);
                    item.setModel("update");
                    item.setMethods(new ArrayList<>());
                    return item;
                });
                group.getMethods().add(method);
            }
        }
        return new ArrayList<>(groups.values());
    }

    private MethodDifferenceSummary parseMethodDifference(VersionCompareReport.Difference difference) {
        String raw = difference.getValue();
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String className = null;
        String methodName = null;
        String methodDesc = "";
        String[] tabParts = raw.split("\t");
        if (tabParts.length >= 3) {
            className = tabParts[0];
            methodName = tabParts[1];
            methodDesc = tabParts[2];
        } else {
            String[] spaceParts = raw.split(" ");
            if (spaceParts.length >= 3) {
                className = spaceParts[0];
                methodName = spaceParts[1];
                methodDesc = spaceParts[2];
            } else if (spaceParts.length == 2) {
                className = spaceParts[0];
                methodName = spaceParts[1];
            }
        }
        if (!StringUtils.hasText(className) || !StringUtils.hasText(methodName)) {
            return null;
        }
        MethodDifferenceSummary summary = new MethodDifferenceSummary();
        summary.setClassName(className);
        summary.setMethodName(methodName);
        summary.setMethodDesc(methodDesc);
        summary.setModel(difference.getModel());
        return summary;
    }

    private List<UsecaseImpactSummary> buildUsecaseImpacts(VersionCompareReport report) {
        LinkedHashMap<String, UsecaseImpactSummary> resultMap = new LinkedHashMap<>();
        if (report.getCases() != null) {
            for (VersionCompareReport.ImpactCase impactCase : report.getCases()) {
                if (impactCase == null || !StringUtils.hasText(impactCase.getCaseId())) {
                    continue;
                }
                String[] differences = impactCase.getDifferences() == null ? new String[0] : impactCase.getDifferences();
                try {
                    UsecaseVo usecase = usecaseService.getUsecase(report.getProjectId(), impactCase.getCaseId());
                    resultMap.put(usecase.getId(), toUsecaseImpactSummary(report, usecase, differences));
                } catch (Exception ignore) {
                    resultMap.putIfAbsent(impactCase.getCaseId(), toMissingUsecaseImpactSummary(impactCase.getCaseId(), differences));
                }
            }
        }
        if (resultMap.isEmpty() && report.getImpactCaseCount() > 0) {
            buildUsecaseImpactsFromJobLog(report).forEach(summary -> resultMap.putIfAbsent(summary.getId(), summary));
        }
        List<UsecaseImpactSummary> result = new ArrayList<>(resultMap.values());
        result.sort(Comparator.comparing(UsecaseImpactSummary::getDirectoryPath, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(UsecaseImpactSummary::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    private UsecaseImpactSummary toUsecaseImpactSummary(VersionCompareReport report, UsecaseVo usecase, String[] differences) {
        UsecaseImpactSummary summary = new UsecaseImpactSummary();
        summary.setId(usecase.getId());
        summary.setTitle(usecase.getTitle());
        summary.setDirectoryPath(resolveUsecaseDirectoryPath(report.getProjectId(),
                Optional.ofNullable(usecase.getDirectory()).orElse("root")));
        summary.setDifferences(differences == null ? new String[0] : differences);
        summary.setLabels(usecase.getLabels() == null ? new String[0] : usecase.getLabels());
        summary.setAvailable(true);
        return summary;
    }

    private List<UsecaseImpactSummary> buildUsecaseImpactsFromJobLog(VersionCompareReport report) {
        List<UsecaseImpactSummary> result = new ArrayList<>();
        if (!StringUtils.hasText(report.getJobLog())) {
            return result;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("命中用例ID：([^，,\\n\\r]+(?:[,，]\\s*[^，,\\n\\r]+)*)").matcher(report.getJobLog());
        while (matcher.find()) {
            Arrays.stream(matcher.group(1).split("[,，]"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .filter(id -> !"-".equals(id))
                    .forEach(ids::add);
        }
        for (String id : ids) {
            try {
                UsecaseVo usecase = usecaseService.getUsecase(report.getProjectId(), id);
                result.add(toUsecaseImpactSummary(report, usecase, inferUsecaseDifferencesFromLog(report.getJobLog(), id)));
            } catch (Exception ignore) {
                result.add(toMissingUsecaseImpactSummary(id, inferUsecaseDifferencesFromLog(report.getJobLog(), id)));
            }
        }
        return result;
    }

    private UsecaseImpactSummary toMissingUsecaseImpactSummary(String usecaseId, String[] differences) {
        UsecaseImpactSummary summary = new UsecaseImpactSummary();
        summary.setId(usecaseId);
        summary.setTitle("用例 " + usecaseId + "（详情不可用）");
        summary.setDirectoryPath("历史报告 / 用例详情不可用");
        summary.setDifferences(differences == null ? new String[0] : differences);
        summary.setLabels(new String[] { "详情不可用" });
        summary.setAvailable(false);
        return summary;
    }

    private String[] inferUsecaseDifferencesFromLog(String jobLog, String usecaseId) {
        if (!StringUtils.hasText(jobLog) || !StringUtils.hasText(usecaseId)) {
            return new String[0];
        }
        LinkedHashSet<String> differences = new LinkedHashSet<>();
        for (String line : jobLog.split("\\r?\\n")) {
            if (!line.contains(usecaseId)) {
                continue;
            }
            Matcher matcher = Pattern.compile("类名：([^\\s，,]+)").matcher(line);
            if (matcher.find()) {
                differences.add(matcher.group(1));
            }
        }
        return differences.toArray(new String[0]);
    }

    private List<EndpointImpactSummary> buildEndpointImpacts(VersionCompareReport report) {
        List<EndpointImpactSummary> result = new ArrayList<>();
        if (report == null || !StringUtils.hasText(report.getAppId())) {
            return result;
        }

        List<DifferenceGroupSummary> differences = buildDifferenceGroups(report);
        if (differences.isEmpty()) {
            return result;
        }

        Map<String, DifferenceGroupSummary> classMap = new LinkedHashMap<>();
        for (DifferenceGroupSummary group : differences) {
            if (group != null && StringUtils.hasText(group.getClassName())) {
                classMap.put(normalizeClassKey(group.getClassName()), group);
            }
        }
        if (classMap.isEmpty()) {
            return result;
        }

        List<ApiEndpointViewVo> endpoints;
        try {
            endpoints = apiEndpointAnalysisService.listByAppId(report.getAppId());
        } catch (Exception ex) {
            return result;
        }
        if (endpoints == null || endpoints.isEmpty()) {
            return result;
        }
        for (ApiEndpointViewVo endpoint : endpoints) {
            if (endpoint == null) {
                continue;
            }
            EndpointImpactSummary matched = matchEndpointImpact(endpoint, classMap);
            if (matched != null) {
                result.add(matched);
            }
        }
        result.sort(Comparator.comparing(EndpointImpactSummary::getEndpointType, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(EndpointImpactSummary::getUrl, Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    private EndpointImpactSummary matchEndpointImpact(ApiEndpointViewVo endpoint, Map<String, DifferenceGroupSummary> classMap) {
        LinkedHashSet<String> matchedClasses = new LinkedHashSet<>();
        LinkedHashSet<String> matchedMethods = new LinkedHashSet<>();

        List<String> endpointClasses = endpoint.getClassNameList() != null && !endpoint.getClassNameList().isEmpty()
                ? endpoint.getClassNameList()
                : Collections.singletonList(endpoint.getClassName());
        List<String> endpointMethods = endpoint.getMethodNameList() != null && !endpoint.getMethodNameList().isEmpty()
                ? endpoint.getMethodNameList()
                : Collections.singletonList(endpoint.getMethodName());

        List<String> normalizedEndpointMethods = endpointMethods.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toList());

        for (String className : endpointClasses) {
            DifferenceGroupSummary group = findDifferenceGroupByClass(classMap, className);
            if (group == null) {
                continue;
            }
            matchedClasses.add(group.getClassName());
            if (group.getMethods() == null || group.getMethods().isEmpty()) {
                matchedMethods.add(group.getModel() == null ? "class" : group.getModel());
                continue;
            }
            for (MethodDifferenceSummary method : group.getMethods()) {
                if (!normalizedEndpointMethods.isEmpty() && normalizedEndpointMethods.stream()
                        .noneMatch(name -> name.equals(method.getMethodName()))) {
                    continue;
                }
                matchedMethods.add(method.getMethodName());
            }
        }

        if (matchedClasses.isEmpty()) {
            return null;
        }
        EndpointImpactSummary summary = new EndpointImpactSummary();
        summary.setId(endpoint.getId());
        summary.setEndpointType(endpoint.getEndpointType());
        summary.setUrl(endpoint.getUrl());
        summary.setHttpMethod(endpoint.getHttpMethod());
        summary.setClassName(endpoint.getClassName());
        summary.setMethodName(endpoint.getMethodName());
        summary.setCovered(endpoint.isCovered());
        summary.setHitCount(endpoint.getHitCount());
        summary.setMatchedClasses(new ArrayList<>(matchedClasses));
        summary.setMatchedMethods(new ArrayList<>(matchedMethods));
        summary.setLinkedUsecases(toEndpointLinkedUsecases(endpoint.getLinkedUsecases()));
        return summary;
    }

    private List<EndpointLinkedUsecaseSummary> toEndpointLinkedUsecases(List<ApiEndpointViewVo.UsecaseLinkVo> links) {
        if (links == null || links.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, EndpointLinkedUsecaseSummary> result = new LinkedHashMap<>();
        for (ApiEndpointViewVo.UsecaseLinkVo link : links) {
            if (link == null || !StringUtils.hasText(link.getId()) || result.containsKey(link.getId())) {
                continue;
            }
            EndpointLinkedUsecaseSummary summary = new EndpointLinkedUsecaseSummary();
            summary.setId(link.getId());
            summary.setTitle(link.getTitle());
            summary.setDirectory(link.getDirectory());
            result.put(link.getId(), summary);
        }
        return new ArrayList<>(result.values());
    }

    private DifferenceGroupSummary findDifferenceGroupByClass(Map<String, DifferenceGroupSummary> classMap, String className) {
        String normalized = normalizeClassKey(className);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        DifferenceGroupSummary exact = classMap.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, DifferenceGroupSummary> entry : classMap.entrySet()) {
            String changedClass = entry.getKey();
            if (normalized.endsWith("." + changedClass) || changedClass.endsWith("." + normalized)) {
                return entry.getValue();
            }
            int lastDot = changedClass.lastIndexOf('.');
            String changedSimpleName = lastDot >= 0 ? changedClass.substring(lastDot + 1) : changedClass;
            int endpointLastDot = normalized.lastIndexOf('.');
            String endpointSimpleName = endpointLastDot >= 0 ? normalized.substring(endpointLastDot + 1) : normalized;
            if (StringUtils.hasText(changedSimpleName) && changedSimpleName.equals(endpointSimpleName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String normalizeClassKey(String className) {
        if (!StringUtils.hasText(className)) {
            return "";
        }
        String normalized = className.trim().replace('\\', '/');
        if (normalized.endsWith(".java")) {
            normalized = normalized.substring(0, normalized.length() - ".java".length());
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        if (normalized.contains("/")) {
            normalized = normalized.replace('/', '.');
        }
        String[] sourceMarkers = {"targetes.", "target.classes.", "classes.", "src.main.java.", "src.test.java.", "main.java.", "test.java."};
        for (String marker : sourceMarkers) {
            int index = normalized.indexOf(marker);
            if (index >= 0) {
                normalized = normalized.substring(index + marker.length());
            }
        }
        return normalized.replaceAll("^\\.+", "");
    }

    private String resolveUsecaseDirectoryPath(String projectId, String directoryId) {
        if (!StringUtils.hasText(directoryId) || "root".equalsIgnoreCase(directoryId)) {
            return "ROOT";
        }
        try {
            List<UsecaseDirectoryVo> tiers = usecaseService.getDirectoryTier(projectId, directoryId);
            if (tiers == null || tiers.isEmpty()) {
                return directoryId;
            }
            List<String> names = new ArrayList<>();
            Collections.reverse(tiers);
            for (UsecaseDirectoryVo vo : tiers) {
                if (vo != null && StringUtils.hasText(vo.getName())) {
                    names.add(vo.getName());
                }
            }
            return names.isEmpty() ? directoryId : String.join(" / ", names);
        } catch (Exception ex) {
            return directoryId;
        }
    }

    private CompareReportDetailSummary toCompareReportDetailSummary(VersionCompareReport report) {
        CompareReportDetailSummary summary = new CompareReportDetailSummary();
        summary.setJobId(report.getJobId());
        summary.setProjectId(report.getProjectId());
        summary.setAppId(report.getAppId());
        summary.setJobName(report.getJobName());
        summary.setSourceVersion(report.getSourceVersion());
        summary.setTargetVersion(report.getTargetVersion());
        summary.setGitBranch(report.getGitBranch());
        summary.setGitOldCommit(report.getGitOldCommit());
        summary.setGitNewCommit(report.getGitNewCommit());
        summary.setCreateTimeText(formatDate(report.getCreateTime()));
        summary.setAddClassCount(report.getAddClassCount());
        summary.setUpdateClassCount(report.getUpdateClassCount());
        summary.setDeleteClassCount(report.getDeleteClassCount());
        summary.setAddMethodCount(report.getAddMethodCount());
        summary.setUpdateMethodCount(report.getUpdateMethodCount());
        summary.setDeleteMethodCount(report.getDeleteMethodCount());
        summary.setImpactCaseCount(report.getImpactCaseCount());
        summary.setJobLog(report.getJobLog());
        return summary;
    }

    private AppSummary toAppSummary(AppVo app) {
        if (app == null) {
            return null;
        }
        AppSummary summary = new AppSummary();
        summary.setId(app.getId());
        summary.setName(app.getName());
        summary.setCurrentVersion(app.getCurrentVersion());
        summary.setCurrentBranch(app.getCurrentBranch());
        summary.setCurrentCommitId(app.getCurrentCommitId());
        summary.setRepoConfigured(StringUtils.hasText(app.getRepoAddress()));
        summary.setSourceType("JAVA");
        return summary;
    }

    private String formatDate(java.util.Date date) {
        return date == null ? null : new SimpleDateFormat(DATE_TIME_PATTERN, Locale.CHINA).format(date);
    }
}
