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
                      <counter type="METHOD" missed="2" covered="1"/>
                      <counter type="CLASS" missed="0" covered="1"/>
                      <counter type="COMPLEXITY" missed="3" covered="2"/>
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
        com.oAT.web.persistence.entity.ClassCoverageIndex index = files.get(0).toClassCoverageIndex("app");
        assertEquals(1, index.getCoveredLines());
        assertEquals(List.of(7), index.getTotalLineNumbers());
        assertEquals(List.of(7), index.getCoveredLineNumbers());
        assertEquals(2, index.getTotalBranchTargets());
        assertEquals(1, files.get(0).getReportTotalClasses());
        assertEquals(1, files.get(0).getReportCoveredClasses());
        assertEquals(3, files.get(0).getReportTotalMethods());
        assertEquals(1, files.get(0).getReportCoveredMethods());
        assertEquals(5, files.get(0).getReportTotalComplexity());
        assertEquals(5, index.getTotalComplexity());
    }

    @Test
    void keepsReportCountersWhenSeveralClassesShareOneSourceFile() {
        UniversalCoverageFile file = new JacocoCoverageParser().parse("""
                <report><package name="demo">
                  <class name="demo/Order" sourcefilename="Order.java">
                    <counter type="METHOD" missed="1" covered="2"/>
                    <counter type="CLASS" missed="0" covered="1"/>
                    <counter type="COMPLEXITY" missed="2" covered="4"/>
                  </class>
                  <class name="demo/Order$Item" sourcefilename="Order.java">
                    <counter type="METHOD" missed="2" covered="1"/>
                    <counter type="CLASS" missed="1" covered="0"/>
                    <counter type="COMPLEXITY" missed="3" covered="2"/>
                  </class>
                  <sourcefile name="Order.java">
                    <line nr="7" ci="1"/>
                  </sourcefile>
                </package></report>
                """.getBytes(StandardCharsets.UTF_8)).get(0);

        assertEquals(2, file.getReportTotalClasses());
        assertEquals(1, file.getReportCoveredClasses());
        assertEquals(6, file.getReportTotalMethods());
        assertEquals(3, file.getReportCoveredMethods());
        assertEquals(11, file.getReportTotalComplexity());
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

        com.oAT.web.persistence.entity.ClassCoverageIndex index = file.toClassCoverageIndex("app");
        com.oAT.web.persistence.entity.ClassCoverageIndex.MethodCoverageDetail create = index.getMethods().get(0);
        com.oAT.web.persistence.entity.ClassCoverageIndex.MethodCoverageDetail cancel = index.getMethods().get(1);
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
        com.oAT.web.persistence.entity.ClassCoverageIndex index = files.get(0).toClassCoverageIndex("app");
        assertEquals(2, index.getTotalLines());
        assertEquals(List.of(27, 29), index.getTotalLineNumbers());
        assertEquals(List.of(27, 29), index.getCoveredLineNumbers());
    }

    @Test
    void keepsJacocoBranchTargetsOnTheOwningMethod() {
        UniversalCoverageFile file = new JacocoCoverageParser().parse("""
                <report><package name="web3Server/controller">
                  <class name="web3Server/controller/Web302Controller" sourcefilename="Web302Controller.java">
                    <method name="allHkAmount" desc="()V" line="78">
                      <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                  </class>
                  <sourcefile name="Web302Controller.java">
                    <line nr="78" ci="7" mb="1" cb="1"/>
                    <line nr="79" ci="4" mb="1" cb="1"/>
                    <line nr="80" ci="4" mb="1" cb="1"/>
                    <line nr="81" ci="1" mb="0" cb="0"/>
                  </sourcefile>
                </package></report>
                """.getBytes(StandardCharsets.UTF_8)).get(0);

        com.oAT.web.persistence.entity.ClassCoverageIndex.MethodCoverageDetail method =
                file.toClassCoverageIndex("app").getMethods().get(0);
        assertEquals(6, method.getTotalBranchTargets());
        assertEquals(3, method.getCoveredBranchTargets());
        assertEquals(50.0, method.getBranchRate() * 100);
    }
}
