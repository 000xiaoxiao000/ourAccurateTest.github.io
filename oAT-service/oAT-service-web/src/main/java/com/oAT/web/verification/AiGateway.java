package com.oAT.web.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.aiplatform.client.AiDraftClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single gateway for verification AI calls.
 *
 * <p>All verification AI calls must go through the standalone ai-platform.
 * In-process model execution is intentionally not used here, so AI runtime
 * and application code remain decoupled.
 */
@Component
public class AiGateway {
    private final AiDraftClient aiDraftClient;

    public AiGateway(AiDraftClient aiDraftClient) {
        this.aiDraftClient = aiDraftClient;
    }

    public boolean isAvailable() {
        return aiDraftClient.ping();
    }

    public String chat(String systemPrompt, String userMessage) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("system", systemPrompt);
        context.put("user", userMessage);
        JsonNode response = aiDraftClient.execute("oAT", "model.generate", context);
        if (response == null) {
            throw new IllegalStateException("ai-platform returned empty response");
        }
        JsonNode textNode = response.path("text");
        if (StringUtils.hasText(textNode.asText(null))) {
            return textNode.asText();
        }
        if (response.has("fallback")) {
            throw new IllegalStateException(response.toString());
        }
        return response.toString();
    }
}
