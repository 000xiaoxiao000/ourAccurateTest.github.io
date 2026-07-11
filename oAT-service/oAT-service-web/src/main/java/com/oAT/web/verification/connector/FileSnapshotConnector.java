package com.oAT.web.verification.connector;

import com.oAT.web.verification.model.VerificationModels.SourceType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FileSnapshotConnector implements VerificationConnector {
    @Override
    public String connectorType() {
        return "standard-file";
    }

    @Override
    public ConnectorSnapshot fetch(ConnectorRequest request) {
        Assert.notNull(request, "连接器请求不能为空");
        Assert.notNull(request.assetType(), "资产类型不能为空");
        Assert.hasText(request.content(), "文件或粘贴内容不能为空");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.options() != null) metadata.putAll(request.options());
        metadata.putIfAbsent("automaticSync", false);
        metadata.putIfAbsent("connectorType", connectorType());
        return new ConnectorSnapshot(request.assetType(), SourceType.FILE, request.externalId(), request.externalUrl(),
                request.sourceVersion(), StringUtils.hasText(request.externalId()) ? request.externalId() : "manual-snapshot",
                request.content(), metadata);
    }
}
