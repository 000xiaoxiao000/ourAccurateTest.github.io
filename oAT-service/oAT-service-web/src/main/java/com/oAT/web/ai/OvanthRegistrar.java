package com.oAT.web.ai;

import com.ovanth.client.OvanthDraftClient;
import com.ovanth.client.OvanthProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 应用启动时自动完成 Ovanth AI 平台接入。
 *
 * <p>1. 尝试从本地文件读取已持久化的 token；<br>
 * 2. 调用 handshake 自检，若已准入则跳过；<br>
 * 3. 若未注册或 token 失效，则调用 /api/ai/register 重新上报并获取新 token；<br>
 * 4. 新 token 持久化到本地文件，供下次启动复用。</p>
 *
 * <p>注册失败会记录错误日志，但不会阻断应用启动（AI 能力属于可选依赖）。</p>
 */
@Component
public class OvanthRegistrar implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(OvanthRegistrar.class);

    private final OvanthDraftClient client;
    private final OvanthProperties properties;
    private final String applicationId;
    private final String applicationName;
    private final String applicationOwner;
    private final String applicationDescription;
    private final Path tokenFile;

    public OvanthRegistrar(OvanthDraftClient client,
                           OvanthProperties properties,
                           @Value("${oat.ovanth.application-id:oAT}") String applicationId,
                           @Value("${oat.ovanth.application-name:ourAccurateTest 智溯平台}") String applicationName,
                           @Value("${oat.ovanth.application-owner:}") String applicationOwner,
                           @Value("${oat.ovanth.application-description:需求 / 测试用例 / 源码一致性 AI 分析平台}") String applicationDescription,
                           @Value("${oat.ovanth.token-file:${user.home}/.oAT/.ovanth-token}") String tokenFile) {
        this.client = client;
        this.properties = properties;
        this.applicationId = applicationId;
        this.applicationName = applicationName;
        this.applicationOwner = applicationOwner;
        this.applicationDescription = applicationDescription;
        this.tokenFile = Paths.get(tokenFile);
    }

    @PostConstruct
    public void loadPersistedToken() {
        try {
            if (Files.exists(tokenFile)) {
                String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (StringUtils.hasText(token)) {
                    client.setApplicationToken(token);
                    log.info("Loaded persisted ovanth token from {}", tokenFile);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load persisted ovanth token from {}: {}", tokenFile, e.getMessage());
        }
    }

    @Override
    public void run(String... args) {
        try {
            OvanthDraftClient.HandshakeResult handshake = client.handshake(applicationId);
            if (handshake.allowed()) {
                log.info("Ovanth platform access verified for applicationId={}", applicationId);
                return;
            }
            log.info("Ovanth handshake not allowed ({}), registering applicationId={}",
                    handshake.reason(), applicationId);

            OvanthDraftClient.RegisterResult result = client.register(
                    applicationId, applicationName, applicationOwner, applicationDescription);
            persistToken(result.token());
            log.info("Ovanth registration succeeded for applicationId={}", result.applicationId());
        } catch (Exception e) {
            log.error("Ovanth registration failed for applicationId={}: {}", applicationId, e.getMessage(), e);
        }
    }

    private void persistToken(String token) throws IOException {
        Path parent = tokenFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Files.writeString(tokenFile, token, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Persisted ovanth token to {}", tokenFile);
    }
}
