package com.oAT.web.service.impl;

import com.oAT.web.persistence.SystemRepository;
import com.oAT.web.persistence.entity.*;
import com.oAT.web.persistence.entity.StandardDate;
import com.oAT.web.service.AppService;
import com.oAT.web.service.entity.AppVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
public class AppServiceImpl implements AppService, StandardDate {

    @Autowired
    private SystemRepository systemRepository;
    /**
     * 创建新的应用
     */
    @Override
    public AppVo createApp(App app) {
        Assert.notNull(app, "param 'app' must be not null");
        Assert.hasText(app.getName(), "param 'app.name' must be not null");
        Assert.hasText(app.getCreateProjectId(), "param 'app.projectId' must be not null");
        Assert.hasText(app.getCreateUserId(), "param 'app.createUserId' must be not null");
        if (!StringUtils.hasText(app.getLanguage())) {
            app.setLanguage("JAVA");
        }
        if (!StringUtils.hasText(app.getLanguageConfig())) {
            app.setLanguageConfig("{}");
        }
        SystemIndex systemIndex = systemRepository.save(new SystemIndex(app));
        return convertApp(systemIndex);
    }

    /**
     * 更新应用
     */
    @Override
    public AppVo updateApp(String projectId, AppVo appVo) {
        Project project = systemRepository.findById(projectId).get().getProject();
        SystemIndex appIndex = systemRepository.findById(appVo.getId()).get();
        App app = appIndex.getApp();
        Assert.isTrue(app.getCreateProjectId().equalsIgnoreCase(projectId), String.format("当前项目(projectId=%s)没有权限修改该应用(appId=%s)", project,
                appVo.getId()));
        app.setDescribe(appVo.getDescribe());
        app.setName(appVo.getName());
        app.setSrcName(appVo.getSrcName());
        app.setLanguage(appVo.getLanguage());
        app.setLanguageConfig(appVo.getLanguageConfig());
        app.setRange(appVo.getRange());
        app.setProperties(appVo.getProperties());
        app.setCurrentVersion(appVo.getCurrentVersion());
        app.setCurrentBranch(appVo.getCurrentBranch());
        app.setCurrentCommitId(appVo.getCurrentCommitId());
        if (appVo.getRepoAddress() != null) {
            app.setRepoAddress(appVo.getRepoAddress());
        }
        if (appVo.getRepoUserName() != null) {
            app.setRepoUserName(appVo.getRepoUserName());
        }
        if (appVo.getRepoPassword() != null) {
            app.setRepoPassword(appVo.getRepoPassword());
        }
        appIndex.setApp(app);
        appIndex.setUpdateTime(new java.util.Date());
        systemRepository.save(appIndex);
        return convertApp(appIndex);
    }

    @Override
    public List<AppVo> getAppList(String projectId) {
        List<AppVo> result = new ArrayList<>();
        List<SystemIndex> list = systemRepository.findByAppCreateProjectIdOrAppRange(projectId, App.Range.all.toString());
        for (SystemIndex systemIndex : list) {
            result.add(convertApp(systemIndex));
        }
        return result;
    }

    @Override
    public AppVo getApp(String appId) {
        if (!StringUtils.hasText(appId)) {
            return null;
        }
        Optional<SystemIndex> systemIndex = systemRepository.findById(appId);
        if (systemIndex.isPresent()) {
            return convertApp(systemIndex.get());
        } else {
            // 处理找不到应用的情况，例如返回 null 或者抛出异常
            throw new IllegalArgumentException("找不到应用 id=" + appId);
        }
    }


    @Override
    public AppVo deleteApp(String projectId, String appId) {
        Project project = systemRepository.findById(projectId).get().getProject();
        SystemIndex appIndex = systemRepository.findById(appId).get();
        App app = appIndex.getApp();
        Assert.isTrue(app.getCreateProjectId().equalsIgnoreCase(projectId), String.format("当前项目 %s 没有权限修改该应用(%s)", project.getName(), app.getName()));
        systemRepository.deleteById(appId);
        return convertApp(appIndex);
    }

    private AppVo convertApp(SystemIndex systemIndex) {
        AppVo appVo = new AppVo();
        BeanUtils.copyProperties(systemIndex, appVo);
        BeanUtils.copyProperties(systemIndex.getApp(), appVo);
        fillLanguageDefaults(appVo);
        return appVo;
    }

    private void fillLanguageDefaults(AppVo appVo) {
        appVo.setLanguage(StringUtils.hasText(appVo.getLanguage()) ? appVo.getLanguage() : "JAVA");
        if (!StringUtils.hasText(appVo.getLanguageConfig())) {
            appVo.setLanguageConfig("{}");
        }
    }

}
