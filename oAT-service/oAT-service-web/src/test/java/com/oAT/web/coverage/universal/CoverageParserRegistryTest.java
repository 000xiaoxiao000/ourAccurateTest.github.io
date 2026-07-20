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

    @Test
    void parsesJacocoCountersAndMethods() {
        List<UniversalCoverageFile> files = new JacocoCoverageParser().parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report>
                  <package name="demo">
                    <class name="demo/Order" sourcefilename="Order.java">
                      <method name="create" desc="()V" line="7">
                        <counter type="METHOD" missed="0" covered="1"/>
                      </method>
                    </class>
                    <sourcefile name="Order.java">
                      <line nr="7" mi="0" ci="3" mb="1" cb="1"/>
                      <counter type="LINE" missed="2" covered="1"/>
                      <counter type="BRANCH" missed="1" covered="1"/>
                    </sourcefile>
                  </package>
                </report>
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, files.size());
        assertEquals(1, files.get(0).getFunctions().size());
        com.oAT.web.esDao.entity.ClassCoverageIndex index = files.get(0).toClassCoverageIndex("app");
        assertEquals(1, index.getCoveredLines());
        assertEquals(List.of(7), index.getTotalLineNumbers());
        assertEquals(List.of(7), index.getCoveredLineNumbers());
        assertEquals(2, index.getTotalBranchTargets());
    }

    @Test
    void assignsOnlyActualExecutableLinesToEachJacocoMethod() {
        UniversalCoverageFile file = new JacocoCoverageParser().parse("""
                <report><package name="demo">
                  <class name="demo/Order" sourcefilename="Order.java">
                    <method name="create" desc="()V" line="7"><counter type="METHOD" missed="0" covered="1"/></method>
                    <method name="cancel" desc="()V" line="12"><counter type="METHOD" missed="1" covered="0"/></method>
                  </class>
                  <sourcefile name="Order.java">
                    <line nr="7" ci="3"/><line nr="9" ci="0"/>
                    <line nr="12" ci="0"/><line nr="14" ci="0"/>
                  </sourcefile>
                </package></report>
                """.getBytes(StandardCharsets.UTF_8)).get(0);

        com.oAT.web.esDao.entity.ClassCoverageIndex index = file.toClassCoverageIndex("app");
        com.oAT.web.esDao.entity.ClassCoverageIndex.MethodCoverageDetail create = index.getMethods().get(0);
        com.oAT.web.esDao.entity.ClassCoverageIndex.MethodCoverageDetail cancel = index.getMethods().get(1);
        assertEquals("demo/Order", create.getClassName());
        assertEquals(7, create.getStartLine());
        assertEquals(List.of(7, 9), create.getTotalLineNumbers());
        assertEquals(List.of(7), create.getCoveredLineNumbers());
        assertEquals(List.of(12, 14), cancel.getTotalLineNumbers());
        assertEquals(List.of(), cancel.getCoveredLineNumbers());
    }

    @Test
    void parsesJacocoHtmlSourcePagesFromUploadedArchiveSummary() {
        List<UniversalCoverageFile> files = new JacocoCoverageParser().parse("""
                多语言覆盖率资料

                // COVERAGE_FILE: web3Server.controller/Web302Controller.java.html
                <html><body><pre>
                <span id="L27" class="fc">covered</span>
                <span id="L28" class="nc">not code</span>
                <span id="L29" class="pc bpc">partial</span>
                </pre></body></html>
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, files.size());
        assertEquals("web3Server/controller/Web302Controller.java", files.get(0).getFilePath());
        assertEquals(2, files.get(0).getLines().size());
        com.oAT.web.esDao.entity.ClassCoverageIndex index = files.get(0).toClassCoverageIndex("app");
        assertEquals(2, index.getTotalLines());
        assertEquals(List.of(27, 29), index.getTotalLineNumbers());
        assertEquals(List.of(27, 29), index.getCoveredLineNumbers());
    }
}
