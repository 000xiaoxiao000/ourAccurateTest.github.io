package com.oAT.web.coverage.universal;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class JacocoCoverageParser implements CoverageParser {
    @Override
    public SourceType sourceType() {
        return SourceType.JAVA;
    }

    @Override
    public List<UniversalCoverageFile> parse(byte[] payload) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(payload)));
            List<UniversalCoverageFile> result = new ArrayList<>();
            NodeList packages = document.getElementsByTagName("package");
            for (int packageIndex = 0; packageIndex < packages.getLength(); packageIndex++) {
                Element packageNode = (Element) packages.item(packageIndex);
                String packageName = packageNode.getAttribute("name");
                NodeList sourceFiles = packageNode.getElementsByTagName("sourcefile");
                for (int fileIndex = 0; fileIndex < sourceFiles.getLength(); fileIndex++) {
                    Element sourceFile = (Element) sourceFiles.item(fileIndex);
                    String path = packageName.isBlank() ? sourceFile.getAttribute("name") : packageName + "/" + sourceFile.getAttribute("name");
                    UniversalCoverageFile file = new UniversalCoverageFile(SourceType.JAVA, path);
                    NodeList lines = sourceFile.getElementsByTagName("line");
                    for (int lineIndex = 0; lineIndex < lines.getLength(); lineIndex++) {
                        Element line = (Element) lines.item(lineIndex);
                        int number = integer(line.getAttribute("nr"));
                        file.getLines().add(new UniversalCoverageFile.LineCoverage(number, integer(line.getAttribute("ci"))));
                        int missed = integer(line.getAttribute("mb"));
                        int covered = integer(line.getAttribute("cb"));
                        for (int branch = 0; branch < missed + covered; branch++) file.getBranches().add(new UniversalCoverageFile.BranchCoverage(number, branch, branch < covered ? 1 : 0, "jacoco"));
                    }
                    result.add(file);
                }
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法解析 JaCoCo XML 覆盖率报告", exception);
        }
    }

    private int integer(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; } }
}
