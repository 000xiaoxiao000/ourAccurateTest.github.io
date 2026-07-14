package com.oAT.web.verification.connector;

import com.oAT.web.verification.model.VerificationModels.*;

import java.util.List;

/**
 * Connector SPI — all external platform adapters implement this interface.
 * Only read capabilities are mandatory; write-back is optional and gated by configuration.
 */
public interface ConnectorSpi {

    /** Unique connector type identifier — must match ConnectorType enum. */
    String connectorType();

    /**
     * Test connectivity with the supplied configuration.
     * Returns an error message on failure, or null on success.
     */
    String testConnection(ConnectorConfig config);

    /**
     * Fetch raw requirements pages from the external system.
     * Returns plain-text content suitable for AI analysis.
     */
    String fetchRequirementContent(ConnectorConfig config, String scopeRef);

    /**
     * Fetch raw testcase content from the external system.
     */
    String fetchTestcaseContent(ConnectorConfig config, String scopeRef);

    /**
     * Fetch raw defect/bug content from the external system.
     */
    default String fetchDefectContent(ConnectorConfig config, String scopeRef) {
        return "";
    }

    /**
     * Write a finding back to the external system as a bug, task, comment or label.
     * Returns the URL of the created external item, or null if not supported.
     */
    default String writeBackFinding(ConnectorConfig config, Finding finding, String message) {
        return null;
    }

    /** Whether this connector supports writing back to the external system. */
    default boolean supportsWriteBack() {
        return false;
    }
}
