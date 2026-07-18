package com.oAT.web.coverage.universal;

import com.oAT.web.common.UtilJson;
import com.oAT.web.service.entity.AppVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CoverageReportService {
    private final CoverageParserRegistry registry;

    public CoverageReportService(CoverageParserRegistry registry) {
        this.registry = registry;
    }

    public List<UniversalCoverageFile> parse(AppVo app, byte[] payload) {
        if (app == null) throw new IllegalArgumentException("应用不能为空");
        SourceType sourceType = SourceType.from(app.getLanguage());
        String format = configuredFormat(app.getLanguageConfig(), sourceType);
        validateFormat(sourceType, format);
        return registry.parse(sourceType, payload);
    }

    private String configuredFormat(String config, SourceType sourceType) {
        if (!StringUtils.hasText(config)) return defaultFormat(sourceType);
        try {
            Map<String, Object> values = UtilJson.toMap(config);
            Object configured = values == null ? null : values.get("coverageFormat");
            if (configured == null) configured = values == null ? null : values.get("profileFormat");
            return configured == null ? defaultFormat(sourceType) : String.valueOf(configured).trim().toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("应用语言配置不是有效 JSON", exception);
        }
    }

    private void validateFormat(SourceType sourceType, String format) {
        boolean compatible = switch (sourceType) {
            case JAVA -> "jacoco-xml".equals(format);
            case FRONTEND -> "istanbul-json".equals(format);
            case GO -> "go-cover".equals(format) || "lcov".equals(format);
            case PYTHON -> "profile-json".equals(format) || "lcov".equals(format);
            case CPP -> "gcov".equals(format) || "lcov".equals(format);
        };
        if (!compatible) throw new IllegalArgumentException("覆盖率格式 " + format + " 不适用于 " + sourceType);
    }

    private String defaultFormat(SourceType sourceType) {
        return switch (sourceType) {
            case JAVA -> "jacoco-xml";
            case FRONTEND -> "istanbul-json";
            case GO -> "go-cover";
            case PYTHON -> "profile-json";
            case CPP -> "lcov";
        };
    }
}
