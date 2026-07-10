package com.oAT.relay.control;

import com.oAT.relay.model.RelayRequest;
import com.oAT.relay.service.RelayRateLimiter;
import com.oAT.relay.service.RelayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiRelayController {
    private final RelayService relayService;
    private final RelayRateLimiter rateLimiter;

    public ApiRelayController(RelayService relayService, RelayRateLimiter rateLimiter) {
        this.relayService = relayService;
        this.rateLimiter = rateLimiter;
    }

    @RequestMapping(value = "/api/**", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> options(@RequestHeader HttpHeaders headers) {
        return ResponseEntity.noContent()
                .headers(corsHeaders(headers))
                .build();
    }

    @RequestMapping(value = "/api/**", method = {
            RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE
    })
    public ResponseEntity<String> forwardApi(HttpServletRequest request,
                                             @RequestBody(required = false) String body,
                                             @RequestHeader HttpHeaders headers) {
        ResponseEntity<String> rejected = rejectIfNecessary(body, headers);
        if (rejected != null) {
            return withCors(rejected, headers);
        }
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        ResponseEntity<String> response = relayService.handle(new RelayRequest(method, pathWithQuery(request), null, body, headers));
        return withCors(response, headers);
    }

    private ResponseEntity<String> rejectIfNecessary(String body, HttpHeaders headers) {
        if (!rateLimiter.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("rate limit exceeded");
        }
        if (!relayService.isAuthorized(headers.getFirst("X-OAT-Relay-Token"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }
        if (relayService.isPayloadTooLarge(null, body)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("payload too large");
        }
        return null;
    }

    private String pathWithQuery(HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    private ResponseEntity<String> withCors(ResponseEntity<String> response, HttpHeaders requestHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.putAll(corsHeaders(requestHeaders));
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers)
                .body(response.getBody());
    }

    private HttpHeaders corsHeaders(HttpHeaders requestHeaders) {
        String origin = requestHeaders.getFirst(HttpHeaders.ORIGIN);
        String requestedHeaders = requestHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin == null || origin.isBlank() ? "*" : origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, origin == null || origin.isBlank() ? "false" : "true");
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                requestedHeaders == null || requestedHeaders.isBlank() ? "content-type, x-oat-relay-token" : requestedHeaders);
        headers.set(HttpHeaders.VARY, "Origin");
        return headers;
    }
}
