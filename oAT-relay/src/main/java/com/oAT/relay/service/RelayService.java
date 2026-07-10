package com.oAT.relay.service;

import com.oAT.relay.config.RelayProperties;
import com.oAT.relay.model.RelayRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

@Service
public class RelayService {
    private static final Logger logger = LoggerFactory.getLogger(RelayService.class);
    private static final String SUCCESS_BODY = "succeed";
    private static final String FAIL_BODY = "fail";
    private final RelayProperties properties;
    private final RelayForwardClient forwardClient;
    private final RelayQueueService queueService;
    private final RelayMetrics metrics;

    public RelayService(RelayProperties properties, RelayForwardClient forwardClient,
                        RelayQueueService queueService, RelayMetrics metrics) {
        this.properties = properties;
        this.forwardClient = forwardClient;
        this.queueService = queueService;
        this.metrics = metrics;
    }

    public ResponseEntity<String> handle(RelayRequest request) {
        metrics.markReceived();
        if (shouldForwardAsync(request.getPath())) {
            return enqueueOrFallback(request);
        }
        return forwardSync(request);
    }

    public boolean isAuthorized(String token) {
        if (!StringUtils.hasText(properties.getAuthToken())) {
            return true;
        }
        return properties.getAuthToken().equals(token);
    }

    public boolean isPayloadTooLarge(MultiValueMap<String, String> params, String body) {
        long maxBytes = properties.getMaxBodySizeMb() * 1024L * 1024L;
        if (body != null && body.getBytes().length > maxBytes) {
            return true;
        }
        if (params == null || params.isEmpty()) {
            return false;
        }
        long totalBytes = 0;
        for (String key : params.keySet()) {
            totalBytes += key == null ? 0 : key.getBytes().length;
            for (String value : params.get(key)) {
                totalBytes += value == null ? 0 : value.getBytes().length;
                if (totalBytes > maxBytes) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean shouldForwardAsync(String path) {
        if (path != null && path.startsWith("/api/")) {
            return false;
        }
        RelayProperties.ForwardMode mode = properties.getForwardMode();
        if (mode == RelayProperties.ForwardMode.SYNC) {
            return false;
        }
        if (mode == RelayProperties.ForwardMode.ASYNC) {
            return true;
        }
        return false;
    }

    private ResponseEntity<String> enqueueOrFallback(RelayRequest request) {
        if (!properties.getQueue().isEnabled()) {
            return forwardSync(request);
        }
        try {
            if (queueService.offer(request)) {
                return ResponseEntity.ok(SUCCESS_BODY);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            metrics.markEnqueueFailure();
            logger.warn("relay enqueue interrupted, requestId={}, path={}", request.getRequestId(), request.getPath());
        }
        RelayProperties.QueueFullPolicy policy = properties.getQueue().getFullPolicy();
        if (policy == RelayProperties.QueueFullPolicy.SYNC_FALLBACK) {
            return forwardSync(request);
        }
        if (policy == RelayProperties.QueueFullPolicy.REJECT) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("relay queue is full");
        }
        return ResponseEntity.ok(FAIL_BODY);
    }

    private ResponseEntity<String> forwardSync(RelayRequest request) {
        try {
            return forwardClient.forward(request);
        } catch (RestClientException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(FAIL_BODY);
        }
    }
}
