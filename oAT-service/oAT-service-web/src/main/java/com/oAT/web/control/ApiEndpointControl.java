package com.oAT.web.control;

import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.ApiEndpointAnalysisService;
import com.oAT.web.service.AppService;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.entity.AppVo;
import com.oAT.web.service.entity.ProjectMemberVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/api/projects/{projectId}/apps/{appId}/api-endpoints")
public class ApiEndpointControl {
    @Autowired
    private AppService appService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ApiEndpointAnalysisService apiEndpointAnalysisService;
    @Autowired
    private ResourceService resourceService;


    @RequestMapping("/upload")
    @ResponseBody
    public ResultNotified upload(@PathVariable String projectId,
                                 @PathVariable String appId,
                                 @RequestParam(value = "file", required = false) MultipartFile file,
                                 @RequestParam(value = "cachePath", required = false) String cachePath) {
        try {
            if (cachePath != null && !cachePath.trim().isEmpty()) {
                File targetFile = new File(resourceService.getCacheRoot(), cachePath);
                if (!targetFile.exists() || !targetFile.isFile()) {
                    return new ResultNotified(false, "所选源码包不存在，请重新选择");
                }
                apiEndpointAnalysisService.analyzeArtifactFile(appId, targetFile);
                return new ResultNotified(true, "接口扫描完成");
            }
            if (file == null || file.isEmpty()) {
                return new ResultNotified(false, "请先选择源码包");
            }
            apiEndpointAnalysisService.analyzeUploadedArtifact(appId, file);
            return new ResultNotified(true, "接口扫描完成");
        } catch (Exception e) {
            return new ResultNotified(false, e.getMessage());
        }
    }

    @RequestMapping("/pulled-zips")
    @ResponseBody
    public Object listPulledZips(@PathVariable String appId) {
        AppVo app = appService.getApp(appId);
        List<Map<String, Object>> result = new ArrayList<>();
        if (app == null) {
            return result;
        }
        File cacheRoot = new File(resourceService.getCacheRoot());
        collectZipFiles(cacheRoot, cacheRoot, app, result);
        result.sort(Comparator.comparing((Map<String, Object> item) -> (Long) item.get("lastModified")).reversed());
        return result;
    }

    @RequestMapping("/list")
    @ResponseBody
    public Object list(@PathVariable String appId) {
        return apiEndpointAnalysisService.listByAppId(appId);
    }

    private String getLoginUserRole(String projectId, String loginName) {
        List<ProjectMemberVo> members = projectService.getProjectMembers(projectId);
        String loginNameRole = "visitor";
        for (ProjectMemberVo member : members) {
            if (loginName.equals(member.getMemberName())) {
                loginNameRole = String.valueOf(member.getRole());
            }
        }
        return loginNameRole;
    }

    private void collectZipFiles(File root, File current, AppVo app, List<Map<String, Object>> result) {
        File[] files = current.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectZipFiles(root, file, app, result);
                continue;
            }
            String fileName = file.getName();
            if (!fileName.toLowerCase().endsWith(".zip")) {
                continue;
            }
            if (!isPulledSourceZip(fileName, app)) {
                continue;
            }
            String relativePath = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            result.add(Map.of(
                    "fileName", fileName,
                    "cachePath", relativePath,
                    "size", file.length(),
                    "lastModified", file.lastModified(),
                    "lastModifiedText", new Date(file.lastModified()).toString()
            ));
        }
    }

    private boolean isPulledSourceZip(String fileName, AppVo app) {
        if (!fileName.startsWith("git-") || !fileName.endsWith(".zip")) {
            return false;
        }
        if (app == null || app.getCurrentBranch() == null || app.getCurrentBranch().trim().isEmpty()) {
            return true;
        }
        return fileName.startsWith("git-" + app.getCurrentBranch().trim() + "-");
    }
}
