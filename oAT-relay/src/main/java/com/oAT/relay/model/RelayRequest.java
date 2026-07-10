package com.oAT.relay.model;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.UUID;

public class RelayRequest {
    private final String requestId;
    private final HttpMethod method;
    private final String path;
    private final MultiValueMap<String, String> formData;
    private final String body;
    private final HttpHeaders headers;
    private final Instant createTime;
    private int attempts;

    public RelayRequest(HttpMethod method, String path, MultiValueMap<String, String> formData, String body,
                        HttpHeaders headers) {
        this.requestId = UUID.randomUUID().toString();
        this.method = method;
        this.path = path;
        this.formData = formData == null ? new LinkedMultiValueMap<>() : new LinkedMultiValueMap<>(formData);
        this.body = body;
        this.headers = headers == null ? new HttpHeaders() : HttpHeaders.writableHttpHeaders(headers);
        this.createTime = Instant.now();
    }

    public String getRequestId() {
        return requestId;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public MultiValueMap<String, String> getFormData() {
        return formData;
    }

    public String getBody() {
        return body;
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public Instant getCreateTime() {
        return createTime;
    }

    public int getAttempts() {
        return attempts;
    }

    public int nextAttempt() {
        this.attempts++;
        return this.attempts;
    }
}
