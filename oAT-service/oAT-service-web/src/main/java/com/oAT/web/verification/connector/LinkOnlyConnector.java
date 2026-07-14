package com.oAT.web.verification.connector;

import com.oAT.web.verification.model.VerificationModels.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Link-only connector: stores an external URL reference without API integration.
 * Used when a real platform connector is not yet configured.
 */
@Component
public class LinkOnlyConnector implements ConnectorSpi {

    @Override
    public String connectorType() {
        return "LINK_ONLY";
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
    public String writeBackFinding(ConnectorConfig config, Finding finding, String message) {
        return StringUtils.hasText(config.baseUrl()) ? config.baseUrl() : null;
    }

    @Override
    public boolean supportsWriteBack() {
        return true;
    }
}
