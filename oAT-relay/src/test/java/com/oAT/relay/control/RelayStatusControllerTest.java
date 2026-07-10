package com.oAT.relay.control;

import com.oAT.relay.config.RelayProperties;
import com.oAT.relay.model.RelayStatus;
import com.oAT.relay.service.RelayMetrics;
import com.oAT.relay.service.RelayQueueService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelayStatusControllerTest {

    @Test
    void shouldExposeRelayStatus() {
        RelayProperties properties = new RelayProperties();
        properties.setTargetBaseUrl("http://localhost:8899/");
        RelayQueueService queueService = mock(RelayQueueService.class);
        when(queueService.depth()).thenReturn(3);
        RelayMetrics metrics = new RelayMetrics();
        metrics.markReceived();
        metrics.markForwardSuccess();
        RelayStatusController controller = new RelayStatusController(properties, queueService, metrics);

        RelayStatus status = controller.status();

        assertEquals("http://localhost:8899", status.getTargetBaseUrl());
        assertEquals("hybrid", status.getForwardMode());
        assertEquals(3, status.getQueueDepth());
        assertEquals(1, status.getReceivedCount());
        assertEquals(1, status.getForwardSuccessCount());
    }

    @Test
    void shouldExposeRelayDashboardHtml() {
        RelayProperties properties = new RelayProperties();
        properties.setTargetBaseUrl("http://localhost:8899/");
        RelayQueueService queueService = mock(RelayQueueService.class);
        when(queueService.depth()).thenReturn(2);
        RelayMetrics metrics = new RelayMetrics();
        metrics.markReceived();
        RelayStatusController controller = new RelayStatusController(properties, queueService, metrics);

        String html = controller.dashboard();

        assertTrue(html.contains("oAT Relay 控制台"));
        assertTrue(html.contains("http://localhost:8899"));
        assertTrue(html.contains("查看 JSON 状态"));
        assertTrue(html.contains("健康检查"));
    }
}
