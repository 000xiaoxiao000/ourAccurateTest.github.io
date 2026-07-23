package com.oAT.web.service;

import com.oAT.web.persistence.entity.App;
import com.oAT.web.service.entity.AppVo;

import java.util.List;


public interface AppService {
    AppVo createApp(App app);
    AppVo updateApp(String projectId, AppVo app);

    List<AppVo> getAppList(String projectId);

    AppVo getApp(String appId);

    AppVo deleteApp(String projectId, String appId);
}
