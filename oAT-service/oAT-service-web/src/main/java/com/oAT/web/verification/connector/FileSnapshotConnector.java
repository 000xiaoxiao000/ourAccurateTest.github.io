package com.oAT.web.verification.connector;

import com.oAT.web.verification.model.VerificationModels.*;
import org.springframework.stereotype.Component;

/**
 * Built-in connector for plain file / paste imports.
 * Content is already captured at import time, so fetch just returns empty
 * (the caller uses the stored AssetSnapshot content directly).
 */
@Component
public class FileSnapshotConnector implements ConnectorSpi {

    @Override
    public String connectorType() {
        return "FILE";
    }

    @Override
    public String testConnection(ConnectorConfig config) {
        return null;
    }

    @Override
    public String fetchRequirementContent(ConnectorConfig config, String scopeRef) {
        return "";
    }

    @Override
    public String fetchTestcaseContent(ConnectorConfig config, String scopeRef) {
        return "";
    }

    @Override
    public boolean supportsWriteBack() {
        return false;
    }
}
