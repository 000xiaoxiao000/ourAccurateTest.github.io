package com.oAT.web.coverage.universal;

import com.fasterxml.jackson.databind.JsonNode;
import com.oAT.web.common.UtilJson;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class PythonCoverageParser extends LcovCoverageParser {
    @Override
    public SourceType sourceType() {
        return SourceType.PYTHON;
    }

    @Override
    public List<UniversalCoverageFile> parse(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        if (!text.stripLeading().startsWith("{")) return super.parse(payload);
        try {
            JsonNode files = UtilJson.getObjectMapper().readTree(text).path("files");
            List<UniversalCoverageFile> result = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> entries = files.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                UniversalCoverageFile file = new UniversalCoverageFile(SourceType.PYTHON, entry.getKey());
                JsonNode summary = entry.getValue().path("summary");
                for (JsonNode line : entry.getValue().path("executed_lines")) file.getLines().add(new UniversalCoverageFile.LineCoverage(line.asInt(), 1));
                for (JsonNode line : entry.getValue().path("missing_lines")) file.getLines().add(new UniversalCoverageFile.LineCoverage(line.asInt(), 0));
                if (file.getLines().isEmpty() && summary.path("num_statements").asInt() > 0) file.getLines().add(new UniversalCoverageFile.LineCoverage(1, summary.path("covered_lines").asInt() > 0 ? 1 : 0));
                result.add(file);
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法解析 Python coverage.py JSON", exception);
        }
    }
}
