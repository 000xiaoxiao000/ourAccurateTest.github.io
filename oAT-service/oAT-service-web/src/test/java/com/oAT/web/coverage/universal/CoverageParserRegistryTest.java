package com.oAT.web.coverage.universal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageParserRegistryTest {
    @Test
    void selectsAParserForEverySupportedLanguage() {
        CoverageParserRegistry registry = new CoverageParserRegistry(List.of(
                new JacocoCoverageParser(), new IstanbulCoverageParser(), new GoCoverageParser(), new PythonCoverageParser(), new CppCoverageParser()));
        for (SourceType type : SourceType.values()) assertTrue(registry.supports(type));
        assertEquals(1, registry.parse(SourceType.JAVA, jacoco()).size());
        assertEquals(1, registry.parse(SourceType.FRONTEND, "{\"src/a.ts\":{\"path\":\"src/a.ts\",\"statementMap\":{},\"s\":{}}}".getBytes(StandardCharsets.UTF_8)).size());
    }

    private byte[] jacoco() {
        return "<report><package name=\"demo\"><sourcefile name=\"Order.java\"><line nr=\"3\" ci=\"1\" mb=\"0\" cb=\"1\"/></sourcefile></package></report>".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void validatesConfiguredLanguageReportFormat() {
        CoverageReportService service = new CoverageReportService(new CoverageParserRegistry(List.of(
                new JacocoCoverageParser(), new IstanbulCoverageParser(), new GoCoverageParser(), new PythonCoverageParser(), new CppCoverageParser())));
        com.oAT.web.service.entity.AppVo app = new com.oAT.web.service.entity.AppVo();
        app.setLanguage("GO");
        app.setLanguageConfig("{\"profileFormat\":\"go-cover\"}");
        assertEquals(1, service.parse(app, "mode: set\nexample/order.go:1.1,1.2 1 1\n".getBytes(StandardCharsets.UTF_8)).size());
    }
}
