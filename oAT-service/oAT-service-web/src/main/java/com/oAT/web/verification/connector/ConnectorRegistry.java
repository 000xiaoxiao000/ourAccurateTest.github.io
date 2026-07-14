package com.oAT.web.verification.connector;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds all registered ConnectorSpi implementations and routes by connector type.
 */
@Component
public class ConnectorRegistry {

    private final Map<String, ConnectorSpi> byType;

    public ConnectorRegistry(List<ConnectorSpi> connectors) {
        this.byType = connectors.stream()
                .collect(Collectors.toMap(ConnectorSpi::connectorType, Function.identity()));
    }

    public ConnectorSpi get(String connectorType) {
        ConnectorSpi spi = byType.get(connectorType);
        if (spi == null) {
            throw new IllegalArgumentException("未知连接器类型: " + connectorType);
        }
        return spi;
    }

    public boolean supports(String connectorType) {
        return byType.containsKey(connectorType);
    }

    public List<String> availableTypes() {
        return List.copyOf(byType.keySet());
    }
}
