package com.oAT.web.api.map;

import com.oAT.web.domain.ImageData;
import com.oAT.web.domain.ImageElement;
import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceInfo;
import com.oAT.web.exceptions.BusinessException;
import com.oAT.web.service.AppService;
import com.oAT.web.service.entity.AppVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MapAppPayloadService {
    private final AppService appService;
    private final StaticInfoRepository staticInfoRepository;

    public MapAppPayloadService(AppService appService, StaticInfoRepository staticInfoRepository) {
        this.appService = appService;
        this.staticInfoRepository = staticInfoRepository;
    }

    public List<ImageElement> buildAppMapData(String projectId, String appId, String layers) throws BusinessException {
        AppVo app = appService.getApp(appId);
        if (app == null || !projectId.equals(app.getCreateProjectId())) {
            throw new BusinessException("应用不存在或不属于当前项目");
        }
        List<ImageElement> result = new ArrayList<>();
        ImageData appData = new ImageData(app.getId());
        appData.name = app.getName();
        appData.describe = app.getDescribe();
        ImageElement appNode = new ImageElement(appData);
        appNode.group = "nodes";
        appNode.classes = new String[] {"app"};
        result.add(appNode);
        for (StaticSourceInfo source : staticInfoRepository.findByAppId(appId)) {
            if (source.getClassInfo() == null) continue;
            ImageData data = new ImageData(source.getClassInfo().getClassName());
            data.name = source.getClassInfo().getClassName();
            data.packageAndClassName = source.getClassInfo().getClassName();
            ImageElement node = new ImageElement(data);
            node.group = "nodes";
            node.classes = new String[] {"class"};
            result.add(node);
        }
        return result;
    }

    public List<ImageElement> buildTraceStackCodeData(String projectId, String traceId) {
        ImageData data = new ImageData(traceId);
        data.name = traceId;
        ImageElement node = new ImageElement(data);
        node.group = "nodes";
        node.classes = new String[] {"trace"};
        return List.of(node);
    }
}
