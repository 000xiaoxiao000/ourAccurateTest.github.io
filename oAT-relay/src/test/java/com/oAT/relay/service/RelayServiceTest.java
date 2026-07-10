package com.oAT.relay.service;

import com.oAT.relay.config.RelayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelayServiceTest {

    @Test
    void shouldAuthorizeWhenTokenNotConfigured() {
        RelayProperties properties = new RelayProperties();
        RelayService relayService = new RelayService(properties, null, null, new RelayMetrics());

        assertTrue(relayService.isAuthorized(null));
        assertTrue(relayService.isAuthorized("any"));
    }

    @Test
    void shouldValidateConfiguredToken() {
        RelayProperties properties = new RelayProperties();
        properties.setAuthToken("secret");
        RelayService relayService = new RelayService(properties, null, null, new RelayMetrics());

        assertTrue(relayService.isAuthorized("secret"));
        assertFalse(relayService.isAuthorized("wrong"));
    }

    @Test
    void shouldRejectLargeFormPayload() {
        RelayProperties properties = new RelayProperties();
        properties.setMaxBodySizeMb(1);
        RelayService relayService = new RelayService(properties, null, null, new RelayMetrics());
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("data", "x".repeat(1024 * 1024 + 1));

        assertTrue(relayService.isPayloadTooLarge(params, null));
    }

    @Test
    void shouldAcceptSmallFormPayload() {
        RelayProperties properties = new RelayProperties();
        properties.setMaxBodySizeMb(1);
        RelayService relayService = new RelayService(properties, null, null, new RelayMetrics());
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("data", "trace");

        assertFalse(relayService.isPayloadTooLarge(params, null));
    }

    @Test
    void shouldForwardApiSynchronouslyEvenWhenAsyncModeEnabled() {
        RelayProperties properties = new RelayProperties();
        properties.setForwardMode(RelayProperties.ForwardMode.ASYNC);
        RelayForwardClient forwardClient = mock(RelayForwardClient.class);
        RelayQueueService queueService = mock(RelayQueueService.class);
        when(forwardClient.forward(any())).thenReturn(org.springframework.http.ResponseEntity.ok("ok"));
        RelayService relayService = new RelayService(properties, forwardClient, queueService, new RelayMetrics());

        relayService.handle(new com.oAT.relay.model.RelayRequest(
                HttpMethod.POST,
                "/api/projects/p1/apps/a1/coverage/frontend/report",
                null,
                "{}",
                null));

        verify(forwardClient).forward(any());
        verifyNoInteractions(queueService);
    }
}
