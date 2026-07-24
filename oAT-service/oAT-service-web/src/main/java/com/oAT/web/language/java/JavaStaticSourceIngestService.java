package com.oAT.web.language.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oAT.web.logging.LogFields;
import com.oAT.web.persistence.StaticInfoRepository;
import com.oAT.web.persistence.entity.StaticSourceClassInfo;
import com.oAT.web.persistence.entity.StaticSourceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class JavaStaticSourceIngestService {
    private static final Logger logger = LoggerFactory.getLogger(JavaStaticSourceIngestService.class);

    @Autowired
    private StaticInfoRepository staticInfoRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public void saveStaticData(String appId, String data, Executor executor) {
        if (org.apache.commons.lang3.StringUtils.isBlank(appId) || org.apache.commons.lang3.StringUtils.isBlank(data)) {
            return;
        }
        int payloadBytes = data.getBytes(StandardCharsets.UTF_8).length;
        logger.info("event=static_source.ingest.received {}", LogFields.of(LogFields.map(
                "app_id", appId,
                "payload_bytes", payloadBytes)));
        executor.execute(() -> persistStaticData(appId, data));
    }

    private void persistStaticData(String appId, String payload) {
        long startTime = System.currentTimeMillis();
        if (!StringUtils.hasText(payload)) {
            logger.warn("event=static_source.ingest.empty {}", LogFields.of(LogFields.map("app_id", appId)));
            return;
        }

        JsonNode data;
        try {
            data = objectMapper.readTree(payload);
        } catch (Exception e) {
            logger.error("event=static_source.ingest.parse_failed {}", LogFields.of(LogFields.map("app_id", appId)), e);
            return;
        }

        int classCount = data.size();
        logger.info("event=static_source.persist.start {}", LogFields.of(LogFields.map(
                "app_id", appId,
                "class_count", classCount,
                "payload_bytes", payload.getBytes(StandardCharsets.UTF_8).length)));
        try {
            StaticDataPersistStats stats = persistStaticDataToEs(appId, data);
            logger.info("event=static_source.persist.completed {}", LogFields.of(LogFields.map(
                    "app_id", appId,
                    "class_count", classCount,
                    "created", stats.createdCount,
                    "updated", stats.updatedCount,
                    "skipped", stats.skippedCount,
                    "failed", stats.failedCount,
                    "duration_ms", System.currentTimeMillis() - startTime)));
        } catch (Exception e) {
            logger.error("event=static_source.persist.failed {}", LogFields.of(LogFields.map(
                    "app_id", appId,
                    "class_count", classCount,
                    "duration_ms", System.currentTimeMillis() - startTime)), e);
        }
    }

    private StaticDataPersistStats persistStaticDataToEs(String appId, JsonNode data) {
        StaticDataPersistStats stats = new StaticDataPersistStats();
        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            try {
                StaticSourceClassInfo classInfo = objectMapper.treeToValue(entry.getValue(), StaticSourceClassInfo.class);
                if (classInfo != null && StringUtils.hasText(classInfo.getClassName())) {
                    List<StaticSourceInfo> list = staticInfoRepository.findByAppIdAndClassInfo_ClassName(appId, classInfo.getClassName());
                    StaticSourceInfo index;
                    if (!list.isEmpty()) {
                        index = list.get(0);
                        index.setClassInfo(classInfo);
                        index.setUpdateTime(new Date());
                        stats.updatedCount++;
                    } else {
                        index = new StaticSourceInfo(classInfo);
                        index.setAppId(appId);
                        stats.createdCount++;
                    }
                    staticInfoRepository.save(index);
                } else {
                    stats.skippedCount++;
                }
            } catch (Exception e) {
                stats.failedCount++;
                logger.error("event=static_source.persist.item_failed {}", LogFields.of(LogFields.map(
                        "app_id", appId,
                        "source_key", entry.getKey())), e);
            }
        }
        return stats;
    }

    private static final class StaticDataPersistStats {
        private int createdCount;
        private int updatedCount;
        private int skippedCount;
        private int failedCount;
    }
}
