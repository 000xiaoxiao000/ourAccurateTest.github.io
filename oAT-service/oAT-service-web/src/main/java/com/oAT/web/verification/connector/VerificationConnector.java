package com.oAT.web.verification.connector;

import com.oAT.web.verification.model.VerificationModels.AssetType;
import com.oAT.web.verification.model.VerificationModels.SourceType;

import java.util.Map;

public interface VerificationConnector {
    String connectorType();

    ConnectorSnapshot fetch(ConnectorRequest request);

    default WriteBackResult writeBack(WriteBackRequest request) {
        return new WriteBackResult(false, null, "当前连接器不支持自动回写，请使用外部链接或人工回写");
    }

    record ConnectorRequest(String projectId, AssetType assetType, String externalId, String externalUrl,
                            String sourceVersion, String content, Map<String, Object> options) {
    }

    record ConnectorSnapshot(AssetType assetType, SourceType sourceType, String externalId, String externalUrl,
                             String sourceVersion, String fileName, String content, Map<String, Object> metadata) {
    }

    record WriteBackRequest(String projectId, String findingId, String externalUrl, String title,
                            String description, Map<String, Object> payload) {
    }

    record WriteBackResult(boolean supported, String externalUrl, String message) {
    }
}
