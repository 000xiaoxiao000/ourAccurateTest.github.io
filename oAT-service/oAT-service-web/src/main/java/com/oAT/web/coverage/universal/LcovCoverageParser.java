package com.oAT.web.coverage.universal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class LcovCoverageParser implements CoverageParser {
    @Override
    public List<UniversalCoverageFile> parse(byte[] payload) {
        Map<String, UniversalCoverageFile> files = new LinkedHashMap<>();
        UniversalCoverageFile current = null;
        for (String line : new String(payload, StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith("SF:")) {
                String path = normalize(line.substring(3));
                current = files.computeIfAbsent(path, ignored -> new UniversalCoverageFile(sourceType(), path));
            } else if (current != null && line.startsWith("DA:")) {
                String[] values = line.substring(3).split(",", 2);
                if (values.length == 2) current.getLines().add(new UniversalCoverageFile.LineCoverage(integer(values[0]), integer(values[1])));
            } else if (current != null && line.startsWith("FN:")) {
                String[] values = line.substring(3).split(",", 2);
                if (values.length == 2) current.getFunctions().add(new UniversalCoverageFile.FunctionCoverage(values[1], integer(values[0]), integer(values[0]), 0));
            } else if (current != null && line.startsWith("FNDA:")) {
                String[] values = line.substring(5).split(",", 2);
                if (values.length == 2) applyFunctionHit(current, values[1], integer(values[0]));
            } else if (current != null && line.startsWith("BRDA:")) {
                String[] values = line.substring(5).split(",", 4);
                if (values.length == 4) current.getBranches().add(new UniversalCoverageFile.BranchCoverage(integer(values[0]), integer(values[2]), "-".equals(values[3]) ? 0 : integer(values[3]), values[1]));
            }
        }
        return new ArrayList<>(files.values());
    }

    private void applyFunctionHit(UniversalCoverageFile file, String name, int hit) {
        for (UniversalCoverageFile.FunctionCoverage function : file.getFunctions()) {
            if (function.getName().equals(name)) function.setCoveredCount(hit);
        }
    }
    private int integer(String value) { try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { return 0; } }
    private String normalize(String path) { String result = path.replace('\\', '/'); while (result.startsWith("/")) result = result.substring(1); return result; }
}
