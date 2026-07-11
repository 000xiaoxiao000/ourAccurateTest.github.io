package com.oAT.web.collector;

import com.oAT.web.service.AppService;
import com.oAT.web.service.entity.AppVo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CollectorSourceService {
    private final AppService appService;

    public CollectorSourceService(AppService appService) {
        this.appService = appService;
    }

    public List<CollectorSource> listProjectSources(String projectId) {
        return appService.getAppList(projectId).stream().map(this::fromApp).toList();
    }

    private CollectorSource fromApp(AppVo app) {
        CollectorSource source = new CollectorSource();
        source.setSourceId(app.getId());
        source.setProjectId(app.getCreateProjectId());
        source.setAppId(app.getId());
        source.setAppName(app.getName());
        source.setLanguage(app.getLanguage());
        source.setCollectorType(CollectorSource.CollectorType.BATCH);
        source.setHealth(app.getOnlineCount() > 0 ? CollectorSource.Health.ONLINE : CollectorSource.Health.OFFLINE);
        return source;
    }
}
