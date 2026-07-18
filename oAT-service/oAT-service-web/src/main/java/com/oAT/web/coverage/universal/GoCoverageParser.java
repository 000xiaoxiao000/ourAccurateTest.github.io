package com.oAT.web.coverage.universal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class GoCoverageParser extends LcovCoverageParser {
    @Override
    public SourceType sourceType() {
        return SourceType.GO;
    }

    @Override
    public List<UniversalCoverageFile> parse(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        if (text.startsWith("mode:")) return parseGoCover(text);
        return super.parse(payload);
    }

    private List<UniversalCoverageFile> parseGoCover(String report) {
        List<UniversalCoverageFile> files = new ArrayList<>();
        java.util.Map<String, UniversalCoverageFile> byPath = new java.util.LinkedHashMap<>();
        for (String row : report.split("\\R")) {
            if (row.startsWith("mode:") || row.isBlank()) continue;
            String[] fields = row.trim().split("\\s+");
            if (fields.length != 3) continue;
            String[] location = fields[0].split(":", 2);
            String[] range = location.length == 2 ? location[1].split(",", 2) : new String[0];
            if (range.length != 2) continue;
            int start = line(range[0]);
            int end = line(range[1]);
            if (start <= 0 || end < start) continue;
            int hits = integer(fields[2]);
            UniversalCoverageFile file = byPath.computeIfAbsent(location[0], path -> new UniversalCoverageFile(SourceType.GO, path));
            for (int line = start; line <= end; line++) file.getLines().add(new UniversalCoverageFile.LineCoverage(line, hits));
        }
        files.addAll(byPath.values());
        return files;
    }

    private int line(String point) { int separator = point.indexOf('.'); return integer(separator >= 0 ? point.substring(0, separator) : point); }
    private int integer(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; } }
}
