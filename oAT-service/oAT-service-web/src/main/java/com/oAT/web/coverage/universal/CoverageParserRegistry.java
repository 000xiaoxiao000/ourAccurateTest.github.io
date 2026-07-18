package com.oAT.web.coverage.universal;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class CoverageParserRegistry {
    private final Map<SourceType, CoverageParser> parsers;

    public CoverageParserRegistry(List<CoverageParser> parsers) {
        Map<SourceType, CoverageParser> resolved = new EnumMap<>(SourceType.class);
        parsers.forEach(parser -> resolved.put(parser.sourceType(), parser));
        this.parsers = Map.copyOf(resolved);
    }

    public List<UniversalCoverageFile> parse(SourceType sourceType, byte[] payload) {
        if (sourceType == null) throw new IllegalArgumentException("必须指定覆盖率报告的源代码类型");
        CoverageParser parser = parsers.get(sourceType);
        if (parser == null) throw new IllegalArgumentException("不支持 " + sourceType + " 的覆盖率报告");
        return parser.parse(payload);
    }

    public boolean supports(SourceType sourceType) {
        return sourceType != null && parsers.containsKey(sourceType);
    }
}
