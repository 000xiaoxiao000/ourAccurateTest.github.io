package com.oAT.web.domain;

import com.oAT.web.service.entity.AppVo;

import java.util.List;
import java.util.stream.Collectors;
public class AppLayer implements ImageLayer {

    private List<AppVo> apps;

    public AppLayer(List<AppVo> apps) {
        this.apps = apps;
    }

    @Override
    public List<ImageElement> elements() {
        return apps.stream().map(a -> {
            ImageData imageData = new ImageData(a.getId());
            imageData.weight = 60;
            imageData.name = a.getName();
            imageData.describe = a.getDescribe();
            return imageData;
        }).map(a -> {
            ImageElement element = buildDefaultNode(a);
            element.classes = new String[]{"app"};
            return element;
        }).collect(Collectors.toList());
    }

}
