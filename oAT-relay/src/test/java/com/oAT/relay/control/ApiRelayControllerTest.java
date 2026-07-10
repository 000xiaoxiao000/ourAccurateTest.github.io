package com.oAT.relay.control;

import com.oAT.relay.model.RelayRequest;
import com.oAT.relay.service.RelayRateLimiter;
import com.oAT.relay.service.RelayService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiRelayControllerTest {

    @Test
    void shouldForwardApiPathWithBodyAndQuery() {
        RelayService relayService = mock(RelayService.class);
        RelayRateLimiter rateLimiter = mock(RelayRateLimiter.class);
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(relayService.isAuthorized(null)).thenReturn(true);
        when(relayService.isPayloadTooLarge(any(), any())).thenReturn(false);
        when(relayService.handle(any())).thenReturn(ResponseEntity.ok("{\"result\":true}"));
        ApiRelayController controller = new ApiRelayController(relayService, rateLimiter);
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/projects/p1/apps/a1/coverage/frontend/report");
        request.setQueryString("debug=1");

        ResponseEntity<String> response = controller.forwardApi(request, "{\"coverage\":{}}", new HttpHeaders());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<RelayRequest> captor = ArgumentCaptor.forClass(RelayRequest.class);
        verify(relayService).handle(captor.capture());
        assertEquals("/api/projects/p1/apps/a1/coverage/frontend/report?debug=1", captor.getValue().getPath());
        assertEquals("{\"coverage\":{}}", captor.getValue().getBody());
    }

    @Test
    void shouldHandleCorsPreflight() {
        RelayService relayService = mock(RelayService.class);
        RelayRateLimiter rateLimiter = mock(RelayRateLimiter.class);
        ApiRelayController controller = new ApiRelayController(relayService, rateLimiter);
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:5173");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");

        ResponseEntity<String> response = controller.options(headers);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals("http://localhost:5173", response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("content-type", response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
    }
}
