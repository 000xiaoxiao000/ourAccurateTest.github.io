package com.oAT.web.api.map;

import com.oAT.web.domain.AppLayer;
import com.oAT.web.domain.ImageElement;
import com.oAT.web.service.AppService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MapHomePayloadService {
    private final AppService appService;

    public MapHomePayloadService(AppService appService) {
        this.appService = appService;
    }

    public List<ImageElement> buildHomeMapData(String projectId) {
        return new AppLayer(appService.getAppList(projectId)).elements();
    }
}
