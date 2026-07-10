package com.oAT.web.api.ai;

import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceClassInfo;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.service.AppService;
import com.oAT.web.service.entity.AppVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AgentStaticSourceLookupService {
    private static final Logger logger = LoggerFactory.getLogger(AgentStaticSourceLookupService.class);

    private final AppService appService;
    private final StaticInfoRepository staticInfoRepository;

    public AgentStaticSourceLookupService(AppService appService, StaticInfoRepository staticInfoRepository) {
        this.appService = appService;
        this.staticInfoRepository = staticInfoRepository;
    }

    public String findSourceCode(String className) {
        if (!StringUtils.hasText(className)) {
            return null;
        }
        String targetClass = className.trim();
        String normalizedTarget = normalizeClassName(targetClass);
        String simpleName = simpleClassName(targetClass);

        List<AppVo> allApps = appService.getAppList(null);
        if (allApps == null || allApps.isEmpty()) {
            return null;
        }

        for (AppVo app : allApps) {
            try {
                List<StaticSourceInfo> appInfos = staticInfoRepository.findByAppId(app.getId());
                String source = findSourceCodeInAppInfos(appInfos, targetClass, normalizedTarget, simpleName);
                if (source != null) {
                    return source;
                }
            } catch (Exception e) {
                logger.debug("getSourceCode scan app failed: appId={}, className={}, reason={}",
                        app.getId(), targetClass, e.getMessage());
            }
        }
        return null;
    }

    public Map<String, StaticSourceInfo> findClassStaticInfo(String className) {
        Map<String, StaticSourceInfo> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(className)) {
            return result;
        }

        String targetClass = className.trim();
        String normalizedTarget = normalizeClassName(targetClass);
        String simpleName = simpleClassName(targetClass);
        try {
            List<AppVo> apps = appService.getAppList(null);
            if (apps == null || apps.isEmpty()) {
                return result;
            }
            for (AppVo app : apps) {
                try {
                    List<StaticSourceInfo> infos = staticInfoRepository.findByAppId(app.getId());
                    if (infos == null || infos.isEmpty()) {
                        continue;
                    }
                    for (StaticSourceInfo info : infos) {
                        StaticSourceClassInfo classInfo = info.getClassInfo();
                        if (classInfo == null || !StringUtils.hasText(classInfo.getClassName())) {
                            continue;
                        }
                        String candidateClassName = classInfo.getClassName().trim();
                        if (matchesClassName(candidateClassName, normalizeClassName(candidateClassName), simpleClassName(candidateClassName),
                                targetClass, normalizedTarget, simpleName)) {
                            result.put(app.getId(), info);
                            break;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("findClassStaticInfo scan app failed: appId={}, className={}, reason={}",
                            app.getId(), className, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("findClassStaticInfo failed: {}", e.getMessage());
        }
        return result;
    }

    public StaticSourceMethodInfo findTargetMethodInfo(Collection<StaticSourceInfo> classInfos, String methodName) {
        if (classInfos == null || classInfos.isEmpty() || !StringUtils.hasText(methodName)) {
            return null;
        }
        String normalizedTarget = normalizeMethodName(methodName);
        for (StaticSourceInfo info : classInfos) {
            if (info == null || info.getClassInfo() == null || info.getClassInfo().getMethodMaps() == null) {
                continue;
            }
            for (Map.Entry<String, StaticSourceMethodInfo> entry : info.getClassInfo().getMethodMaps().entrySet()) {
                StaticSourceMethodInfo methodInfo = entry.getValue();
                if (methodInfo == null) {
                    continue;
                }
                if (matchesMethodName(methodInfo.getMethodName(), entry.getKey(), normalizedTarget, methodName)) {
                    return methodInfo;
                }
            }
        }
        return null;
    }

    public Map<String, Object> searchCodeRelation(String projectId, String keyword) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> interfaceList = new ArrayList<>();
        List<Map<String, Object>> classList = new ArrayList<>();
        try {
            List<AppVo> apps = appService.getAppList(projectId);
            if (apps == null || apps.isEmpty()) {
                result.put("interfaces", interfaceList);
                result.put("classes", classList);
                return result;
            }
            String lowerKeyword = keyword.toLowerCase();
            int matchLimit = 30;

            for (AppVo app : apps) {
                if (interfaceList.size() + classList.size() >= matchLimit * 2) {
                    break;
                }
                try {
                    List<StaticSourceInfo> infos = staticInfoRepository.findByAppId(app.getId());
                    if (infos == null) {
                        continue;
                    }
                    for (StaticSourceInfo info : infos) {
                        StaticSourceClassInfo classInfo = info.getClassInfo();
                        if (classInfo == null || classInfo.getClassName() == null) {
                            continue;
                        }
                        String fullClassName = classInfo.getClassName();
                        boolean nameMatches = fullClassName.toLowerCase().contains(lowerKeyword)
                                || simpleClassName(fullClassName).toLowerCase().contains(lowerKeyword);

                        if (!nameMatches && classInfo.getMethodMaps() != null) {
                            for (StaticSourceMethodInfo methodInfo : classInfo.getMethodMaps().values()) {
                                if (methodInfo != null
                                        && methodInfo.getMethodName() != null
                                        && methodInfo.getMethodName().toLowerCase().contains(lowerKeyword)) {
                                    nameMatches = true;
                                    break;
                                }
                            }
                        }
                        if (!nameMatches) {
                            continue;
                        }

                        Map<String, Object> cls = new HashMap<>();
                        cls.put("name", fullClassName);
                        cls.put("package", extractPackageName(fullClassName));
                        cls.put("simpleName", simpleClassName(fullClassName));
                        cls.put("appName", app.getName());
                        classList.add(cls);
                    }
                } catch (Exception e) {
                    logger.warn("Search code relation for app {} failed: {}", app.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Search code relation failed: projectId={}, keyword={}", projectId, keyword, e);
        }

        result.put("interfaces", interfaceList);
        result.put("classes", classList);
        return result;
    }

    public Map<String, Object> getCallGraph(String className, String methodName) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> callers = new ArrayList<>();
        List<Map<String, Object>> callees = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();

        try {
            Map<String, StaticSourceInfo> classInfos = findClassStaticInfo(className);
            boolean classFound = StringUtils.hasText(className) && !classInfos.isEmpty();
            boolean methodFound = !StringUtils.hasText(methodName);
            if (classFound && StringUtils.hasText(methodName)) {
                methodFound = findTargetMethodInfo(classInfos.values(), methodName) != null;
            }

            result.put("classFound", classFound);
            result.put("methodFound", methodFound);
            result.put("callers", callers);
            result.put("callees", callees);
            result.put("traces", traces);

            if (!classFound) {
                logger.info("No static source info found for call graph: className={}, methodName={}", className, methodName);
                return result;
            }
            if (StringUtils.hasText(methodName) && !methodFound) {
                logger.info("No static method info found for call graph: className={}, methodName={}", className, methodName);
                return result;
            }

            logger.info("No real method call graph data available for className={}, methodName={}; current data source only stores method metadata, not invocation edges.",
                    className, methodName);
            return result;
        } catch (Exception e) {
            logger.error("Get call graph failed: className={}, methodName={}", className, methodName, e);
            result.put("classFound", false);
            result.put("methodFound", false);
            result.put("callers", callers);
            result.put("callees", callees);
            result.put("traces", traces);
            return result;
        }
    }

    public String simpleClassName(String fullClassName) {
        if (!StringUtils.hasText(fullClassName)) {
            return "";
        }
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
    }

    public String extractPackageName(String fullClassName) {
        if (!StringUtils.hasText(fullClassName)) {
            return "";
        }
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot > 0 ? fullClassName.substring(0, lastDot) : "default";
    }

    private String findSourceCodeInAppInfos(List<StaticSourceInfo> appInfos, String targetClass, String normalizedTarget, String simpleName) {
        if (appInfos == null || appInfos.isEmpty()) {
            return null;
        }
        for (StaticSourceInfo info : appInfos) {
            StaticSourceClassInfo classInfo = info.getClassInfo();
            if (classInfo == null || classInfo.getClassName() == null || classInfo.getSourceCode() == null) {
                continue;
            }
            String candidateClassName = classInfo.getClassName().trim();
            String normalizedCandidate = normalizeClassName(candidateClassName);
            String candidateSimpleName = simpleClassName(candidateClassName);

            if (matchesClassName(candidateClassName, normalizedCandidate, candidateSimpleName, targetClass, normalizedTarget, simpleName)) {
                return classInfo.getSourceCode();
            }

            if (normalizedCandidate.contains(normalizedTarget) || normalizedTarget.contains(normalizedCandidate)) {
                return classInfo.getSourceCode();
            }
        }
        return null;
    }

    private boolean matchesClassName(String candidateClassName, String normalizedCandidate, String candidateSimpleName,
                                     String targetClass, String normalizedTarget, String targetSimpleName) {
        if (candidateClassName.equals(targetClass) || normalizedCandidate.equals(normalizedTarget)) {
            return true;
        }
        if (candidateSimpleName.equals(targetSimpleName)) {
            return true;
        }
        return candidateClassName.endsWith("." + targetSimpleName)
                || targetClass.endsWith("." + candidateSimpleName)
                || normalizedCandidate.endsWith("." + normalizedTarget)
                || normalizedTarget.endsWith("." + normalizedCandidate);
    }

    private boolean matchesMethodName(String candidateMethodName, String candidateKey, String normalizedTarget, String targetMethodName) {
        String normalizedCandidate = normalizeMethodName(candidateMethodName);
        String normalizedCandidateKey = normalizeMethodName(candidateKey);
        String normalizedTargetMethod = normalizeMethodName(targetMethodName);
        return normalizedCandidate.equals(normalizedTargetMethod)
                || normalizedCandidateKey.equals(normalizedTargetMethod)
                || normalizedCandidate.contains(normalizedTargetMethod)
                || normalizedTargetMethod.contains(normalizedCandidate)
                || normalizedCandidateKey.contains(normalizedTargetMethod)
                || normalizedTargetMethod.contains(normalizedCandidateKey)
                || normalizedCandidate.equals(normalizedTarget)
                || normalizedCandidateKey.equals(normalizedTarget);
    }

    private String normalizeClassName(String className) {
        if (className == null) {
            return "";
        }
        return className.trim().replace('$', '.').toLowerCase(Locale.ROOT);
    }

    private String normalizeMethodName(String methodName) {
        if (!StringUtils.hasText(methodName)) {
            return "";
        }
        String normalized = methodName.trim();
        int parenIndex = normalized.indexOf('(');
        if (parenIndex > 0) {
            normalized = normalized.substring(0, parenIndex);
        }
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot >= 0) {
            normalized = normalized.substring(lastDot + 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
