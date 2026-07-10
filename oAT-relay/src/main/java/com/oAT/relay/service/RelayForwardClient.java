package com.oAT.relay.service;

import com.oAT.relay.config.RelayProperties;
import com.oAT.relay.model.RelayRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class RelayForwardClient {
    private static final Logger logger = LoggerFactory.getLogger(RelayForwardClient.class);
    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            HttpHeaders.HOST.toLowerCase(),
            HttpHeaders.CONNECTION.toLowerCase(),
            HttpHeaders.CONTENT_LENGTH.toLowerCase(),
            HttpHeaders.TRANSFER_ENCODING.toLowerCase()
    );

    private final RestTemplate restTemplate;
    private final RelayProperties properties;
    private final RelayMetrics metrics;

    public RelayForwardClient(RestTemplate relayRestTemplate, RelayProperties properties, RelayMetrics metrics) {
        this.restTemplate = relayRestTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public ResponseEntity<String> forward(RelayRequest request) {
        String url = properties.getTargetBaseUrl() + request.getPath();
        HttpHeaders headers = buildForwardHeaders(request);
        HttpEntity<?> entity = buildEntity(request, headers);
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, request.getMethod(), entity, String.class);
            metrics.markForwardSuccess();
            logger.info("relay forward succeed, requestId={}, path={}, status={}, costMs={}",
                    request.getRequestId(), request.getPath(), response.getStatusCode().value(),
                    System.currentTimeMillis() - start);
            return response;
        } catch (RestClientException ex) {
            metrics.markForwardFailure();
            logger.warn("relay forward failed, requestId={}, path={}, costMs={}, error={}",
                    request.getRequestId(), request.getPath(), System.currentTimeMillis() - start, ex.getMessage());
            throw ex;
        }
    }

    private HttpHeaders buildForwardHeaders(RelayRequest request) {
        HttpHeaders headers = new HttpHeaders();
        HttpHeaders source = request.getHeaders();
        source.forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, values);
            }
        });
        headers.set("X-OAT-Relay-Source", "oAT-relay");
        headers.set("X-OAT-Relay-Request-Id", request.getRequestId());
        return headers;
    }

    private HttpEntity<?> buildEntity(RelayRequest request, HttpHeaders headers) {
        if (request.getBody() != null) {
            return new HttpEntity<>(request.getBody(), headers);
        }
        MultiValueMap<String, String> formData = request.getFormData();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return new HttpEntity<>(formData, headers);
    }
}
