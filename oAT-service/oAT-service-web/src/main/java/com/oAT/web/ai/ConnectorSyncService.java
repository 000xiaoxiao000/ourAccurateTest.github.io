package com.oAT.web.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.oAT.web.service.entity.ProjectVo;
import com.ovanth.client.OvanthConnectorClient;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.entity.UserVo;
import com.oAT.web.verification.VerificationService;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import com.oAT.web.verification.model.VerificationModels.AssetType;
import com.oAT.web.verification.model.VerificationModels.SourceType;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务线连接器同步服务 —— 把 ovanth 返回的 ConnectorDataItem 写回 oAT verification 库。
 *
 * 这是 oAT 单一权威路径：
 *   - oAT 自己的 ConnectorSpi 链路（JIRA / 禅道 adapter 在 oAT 里）逐步下线，连接器权威只在 ovanth
 *   - 凡 {@code POST /assets/connector-sync} 或 {@code /connectors/invoke} 都走这里
 *
 * 业务线永远不持有外部平台凭证。凭证由 ovanth 控制台配置。
 */
@Service
public class ConnectorSyncService {

    private final OvanthConnectorClient connectorClient;
    private final VerificationService verificationService;
    private final ProjectService projectService;

    public ConnectorSyncService(OvanthConnectorClient connectorClient,
                                VerificationService verificationService,
                                ProjectService projectService) {
        this.connectorClient = connectorClient;
        this.verificationService = verificationService;
        this.projectService = projectService;
    }

    public SyncOutcome invoke(String projectId, UserVo user, String connectorId,
                              JsonNode range, String externalVersion, Integer limit, Boolean writeBack) {
        Assert.hasText(connectorId, "connectorId 不能为空");

        JsonNode resp = connectorClient.invoke(connectorId, range, externalVersion, limit == null ? 0 : limit);

        if (resp == null || !resp.path("success").asBoolean(false)) {
            String code = resp == null ? "OVANTH-EMPTY" : resp.path("error").path("code").asText("OVANTH-FAILED");
            String msg  = resp == null ? "ovanth 返回为空"  : resp.path("error").path("message").asText("ovanth 调用失败");
            throw new IllegalArgumentException(code + ": " + msg);
        }

        JsonNode dataArr = resp.path("dataList");
        int platformCount = dataArr.isArray() ? dataArr.size() : 0;
        if (platformCount == 0) {
            return new SyncOutcome(connectorId, resp.path("connectorType").asText(""),
                    resp.path("invocationId").asText(""), 0, List.of());
        }

        boolean doWrite = writeBack == null || writeBack;
        List<AssetSnapshot> imported = new ArrayList<>();
        if (doWrite) {
            ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
            Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");

            for (JsonNode item : dataArr) {
                String kind = item.path("kind").asText("");
                AssetType assetType = mapKindToAssetType(kind);
                if (assetType == null) continue;
                String content = item.path("content").asText("");
                if (StringUtils.isEmpty(content)) continue;
                String externalId   = item.path("externalId").asText(null);
                String externalUrl  = item.path("url").asText(null);
                String srcVersion   = item.path("externalVersion").asText(externalVersion);
                String title        = item.path("title").asText("未命名");
                String fileName     = "connector-" + assetType.name().toLowerCase() + "-"
                        + (externalId == null ? title.replaceAll("\\s+", "_") : externalId) + ".txt";

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("connectorId", connectorId);
                metadata.put("connectorKind", kind);
                metadata.put("connectorSource", resp.path("connectorType").asText(""));
                metadata.put("connectorInvocationId", resp.path("invocationId").asText(""));
                metadata.put("automaticSync", true);
                metadata.put("inputMode", "CONNECTOR");
                metadata.put("mcpSource", true);
                metadata.put("rawJson", item.path("rawJson").asText("{}"));

                imported.add(verificationService.importAsset(projectId, user.getId(),
                        assetType, SourceType.API, fileName, content, externalId, externalUrl,
                        srcVersion, metadata));
            }
        }

        return new SyncOutcome(connectorId, resp.path("connectorType").asText(""),
                resp.path("invocationId").asText(""), platformCount, imported);
    }

    private AssetType mapKindToAssetType(String kind) {
        if (kind == null) return null;
        return switch (kind.toUpperCase()) {
            case "REQUIREMENT" -> AssetType.REQUIREMENT;
            case "TESTCASE"    -> AssetType.TESTCASE;
            case "DEFECT"      -> AssetType.DEFECT;
            case "SOURCE"      -> AssetType.SOURCE;
            case "COVERAGE_METHOD" -> AssetType.COVERAGE;
            case "EXECUTION"   -> AssetType.EXECUTION;
            default -> null;
        };
    }

    public record SyncOutcome(
            String connectorId,
            String connectorType,
            String invocationId,
            int platformItemCount,
            List<AssetSnapshot> importedAssets) {
    }
}
