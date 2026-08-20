package com.oAT.web.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.ovanth.client.OvanthConnectorClient;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.entity.UserVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.List;

/**
 * 连接器代理（ovanth 权威连接器层）。
 *
 * 暴露给业务线前端的"从平台同步"区域直接入口：
 *   GET  /api/projects/{projectId}/connectors/list          -> 列出当前租户可用连接器（仅 meta, 无 secret）
 *   POST /api/projects/{projectId}/connectors/invoke        -> 拉取并落库
 */
@RestController
@RequestMapping("/api/projects/{projectId}/connectors")
public class ConnectorProxyController {

    private final OvanthConnectorClient connectorClient;
    private final ConnectorSyncService syncService;

    public ConnectorProxyController(OvanthConnectorClient connectorClient, ConnectorSyncService syncService) {
        this.connectorClient = connectorClient;
        this.syncService = syncService;
    }

    @GetMapping("/list")
    public ResultNotified<List<JsonNode>> list() {
        JsonNode arr = connectorClient.listConfigurations();
        List<JsonNode> list = arr == null || !arr.isArray() ? List.of() : List.of(arr).stream().flatMap(j -> {
            List<JsonNode> out = new java.util.ArrayList<>();
            j.forEach(out::add);
            return out.stream();
        }).toList();
        return ok("已配置的连接器列表（无凭证）", list);
    }

    @PostMapping("/invoke")
    public ResultNotified<ConnectorSyncService.SyncOutcome> invoke(@PathVariable String projectId,
                                                                   @SessionAttribute UserVo user,
                                                                   @RequestBody ConnectorInvokeRequest request) {
        return ok("ovanth 连接器调用完成",
                syncService.invoke(projectId, user, request.connectorId(),
                        request.range(), request.externalVersion(),
                        request.limit(), request.writeBack()));
    }

    private <T> ResultNotified<T> ok(String message, T data) {
        return new ResultNotified<>(true, message, data);
    }

    public record ConnectorInvokeRequest(
            String connectorId,
            JsonNode range,
            String externalVersion,
            Integer limit,
            Boolean writeBack) {
    }
}
