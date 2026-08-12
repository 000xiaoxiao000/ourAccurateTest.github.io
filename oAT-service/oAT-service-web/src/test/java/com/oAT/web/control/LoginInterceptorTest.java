package com.oAT.web.control;

import com.aiplatform.client.AiPlatformProperties;
import com.oAT.web.config.FrontendProperties;
import com.oAT.web.logging.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginInterceptorTest {

    @Test
    void aiToolRequestWithConfiguredTokenBypassesSessionAuth() {
        LoginInterceptor interceptor = interceptor("shared-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai-tools/assets/read");
        request.addHeader("X-OAT-AI-TOOL-TOKEN", "shared-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void aiToolRequestWithWrongTokenStillRequiresSessionAuth() {
        LoginInterceptor interceptor = interceptor("shared-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai-tools/assets/read");
        request.addHeader("X-OAT-AI-TOOL-TOKEN", "wrong-token");
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    private LoginInterceptor interceptor(String toolToken) {
        AiPlatformProperties properties = new AiPlatformProperties();
        properties.setToolToken(toolToken);
        return new LoginInterceptor(new FrontendProperties(), properties, new AuditLogger());
    }
}
