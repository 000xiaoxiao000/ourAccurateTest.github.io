package com.oAT.web.control.api;

import com.oAT.web.api.map.MapHomePayloadService;
import com.oAT.web.api.map.MapAppPayloadService;
import com.oAT.web.domain.ImageElement;
import com.oAT.web.exceptions.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/map")
public class MapApiControl {

    private final MapHomePayloadService mapHomePayloadService;
    private final MapAppPayloadService mapAppPayloadService;

    public MapApiControl(MapHomePayloadService mapHomePayloadService,
                         MapAppPayloadService mapAppPayloadService) {
        this.mapHomePayloadService = mapHomePayloadService;
        this.mapAppPayloadService = mapAppPayloadService;
    }

    @GetMapping("/home")
    public List<ImageElement> home(@PathVariable String projectId) {
        return mapHomePayloadService.buildHomeMapData(projectId);
    }

    @GetMapping("/apps/{appId}")
    public List<ImageElement> app(@PathVariable String projectId,
                                  @PathVariable String appId,
                                  @RequestParam(required = false) String layers) throws BusinessException {
        return mapAppPayloadService.buildAppMapData(projectId, appId, layers);
    }

    @GetMapping("/code")
    public List<ImageElement> code(@PathVariable String projectId,
                                   @RequestParam String traceId) {
        return mapAppPayloadService.buildTraceStackCodeData(projectId, traceId);
    }
}
