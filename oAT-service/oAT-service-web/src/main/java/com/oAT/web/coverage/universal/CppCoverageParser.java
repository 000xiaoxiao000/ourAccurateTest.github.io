package com.oAT.web.coverage.universal;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class CppCoverageParser extends LcovCoverageParser {
    @Override
    public SourceType sourceType() {
        return SourceType.CPP;
    }

    @Override
    public List<UniversalCoverageFile> parse(byte[] payload) {
        String report = new String(payload, StandardCharsets.UTF_8);
        return report.contains("SF:") ? super.parse(payload) : parseGcov(report);
    }

    private List<UniversalCoverageFile> parseGcov(String report) {
        String path = "";
        List<UniversalCoverageFile> result = new ArrayList<>();
        UniversalCoverageFile file = null;
        for (String row : report.split("\\R")) {
            if (row.startsWith("Source:")) {
                path = row.substring("Source:".length()).trim();
                file = new UniversalCoverageFile(SourceType.CPP, path);
                result.add(file);
                continue;
            }
            String[] fields = row.split(":", 3);
            if (file == null || fields.length < 3) continue;
            int line = integer(fields[1]);
            if (line <= 0) continue;
            String hits = fields[0].trim();
            file.getLines().add(new UniversalCoverageFile.LineCoverage(line, "#####".equals(hits) || "-".equals(hits) ? 0 : integer(hits.replace("*", ""))));
        }
        if (result.isEmpty() && !path.isBlank()) result.add(new UniversalCoverageFile(SourceType.CPP, path));
        return result;
    }

    private int integer(String value) { try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { return 0; } }
}
