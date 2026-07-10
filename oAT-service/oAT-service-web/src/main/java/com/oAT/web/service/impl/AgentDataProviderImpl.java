package com.oAT.web.service.impl;

import com.oAT.ai.agent.AgentDataProvider;
import com.oAT.ai.agent.cache.ToolCallCache;
import com.oAT.web.api.ai.AgentStaticSourceLookupService;
import com.oAT.web.service.*;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Agent 数据提供者实现
 * 为 AI Agent 提供项目数据查询能力
 */
@Service
public class AgentDataProviderImpl implements AgentDataProvider {

    private static final Logger logger = LoggerFactory.getLogger(AgentDataProviderImpl.class);

    private final ToolCallCache cache = ToolCallCache.getInstance();

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AppService appService;

    @Autowired
    private GitService gitService;

    @Autowired
    private AgentStaticSourceLookupService agentStaticSourceLookupService;

    @Override
    public Map<String, Object> getProjectInfo(String projectId) {
        // 尝试从缓存获取
        String cacheKey = "project:" + projectId;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            logger.debug("Returning cached project info for: {}", projectId);
            // 这里简化处理，实际应该缓存Map对象
        }

        Map<String, Object> result = new HashMap<>();
        try {
            ProjectVo project = projectService.getProject(projectId);
            if (project != null) {
                result.put("id", project.getId());
                result.put("name", project.getName());
                result.put("describe", project.getDescribe());
                result.put("create", project.getCreate());
                result.put("memberCount", project.getMemberCount());
                result.put("createTime", project.getCreateTime());
                result.put("updateTime", project.getUpdateTime());

                // 缓存10分钟
                cache.put(cacheKey, "cached", 10 * 60 * 1000);
            }
        } catch (Exception e) {
            logger.error("Get project info failed: {}", projectId, e);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getApps(String projectId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<AppVo> apps = appService.getAppList(projectId);
            if (apps != null) {
                for (AppVo app : apps) {
                    Map<String, Object> appMap = convertAppToMap(app);
                    appMap.put("online", false);
                    appMap.put("onlineCount", 0);
                    result.add(appMap);
                }
            }
        } catch (Exception e) {
            logger.error("Get apps failed: {}", projectId, e);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getOnlineApps(String projectId) {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> getAppDetail(String appId) {
        Map<String, Object> result = new HashMap<>();
        try {
            AppVo app = appService.getApp(appId);
            if (app != null) {
                result = convertAppToMap(app);
                result.put("online", false);
                result.put("onlineCount", 0);
            }
        } catch (Exception e) {
            logger.error("Get app detail failed: {}", appId, e);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getTraceList(String projectId, String appId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> getTraceDetail(String traceId) {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, Object> searchCodeRelation(String projectId, String keyword) {
        return agentStaticSourceLookupService.searchCodeRelation(projectId, keyword);
    }

    @Override
    public Map<String, Object> getCallGraph(String className, String methodName) {
        return agentStaticSourceLookupService.getCallGraph(className, methodName);
    }

    @Override
    public Map<String, Object> getProjectStatistics(String projectId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<AppVo> apps = appService.getAppList(projectId);
            int appCount = apps != null ? apps.size() : 0;
            result.put("appCount", appCount);
            result.put("onlineAppCount", 0);

            result.put("traceCount", 0);
        } catch (Exception e) {
            logger.error("Get project statistics failed: {}", projectId, e);
        }
        return result;
    }

    @Override
    public String getSourceCode(String className) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        try {
            return agentStaticSourceLookupService.findSourceCode(className);
        } catch (Exception e) {
            logger.error("Get source code failed: className={}", className, e);
            return null;
        }
    }

    @Override
    public Map<String, String> getSourceCodes(List<String> classNames) {
        Map<String, String> result = new LinkedHashMap<>();
        if (classNames == null || classNames.isEmpty()) {
            return result;
        }
        for (String className : classNames) {
            if (className != null && !className.trim().isEmpty()) {
                String code = getSourceCode(className.trim());
                if (code != null) {
                    result.put(className.trim(), code);
                }
            }
        }
        return result;
    }

    @Override
    public boolean validateGitAccess(String repoUrl, String username, String password, String branch) {
        try {
            gitService.checkGitPull(repoUrl, username, password, branch, null);
            return true;
        } catch (Exception e) {
            logger.warn("Git access validation failed: repoUrl={}, branch={}, error={}",
                    repoUrl, branch, e.getMessage());
            return false;
        }
    }

    private Map<String, Object> convertAppToMap(AppVo app) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", app.getId());
        map.put("name", app.getName());
        map.put("describe", app.getDescribe());
        map.put("srcName", app.getSrcName());
        map.put("createTime", app.getCreateTime());
        map.put("currentVersion", app.getCurrentVersion());
        map.put("currentBranch", app.getCurrentBranch());
        map.put("currentCommitId", app.getCurrentCommitId());
        map.put("repoUrl", app.getRepoAddress());
        map.put("gitUsername", app.getRepoUserName());
        map.put("gitPassword", app.getRepoPassword());
        return map;
    }

}
