package com.oAT.web.coverage.universal;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JacocoCoverageParser implements CoverageParser {
    private static final Pattern COVERAGE_FILE_PATTERN = Pattern.compile("(?m)^//\\s*COVERAGE_FILE:\\s*(.+)$");
    private static final Pattern SOURCE_LINE_PATTERN = Pattern.compile("<span[^>]*id=\"L(\\d+)\"[^>]*class=\"([^\"]*)\"[^>]*>");

    @Override
    public SourceType sourceType() {
        return SourceType.JAVA;
    }

    @Override
    public List<UniversalCoverageFile> parse(byte[] payload) {
        String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        if (text.contains("// COVERAGE_FILE:") && text.contains(".html")) {
            List<UniversalCoverageFile> htmlFiles = parseHtmlReport(text);
            if (!htmlFiles.isEmpty()) return htmlFiles;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(emptyEntityResolver());
            Document document = builder.parse(new InputSource(new ByteArrayInputStream(payload)));
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
                    applyCounterFallbacks(file, sourceFile);
                    addMethodCoverage(file, packageNode, sourceFile.getAttribute("name"));
                    result.add(file);
                }
            }
            return result;
        } catch (Exception exception) {
            List<UniversalCoverageFile> htmlFiles = parseHtmlReport(text);
            if (!htmlFiles.isEmpty()) return htmlFiles;
            throw new IllegalArgumentException("无法解析 JaCoCo XML 覆盖率报告", exception);
        }
    }

    private List<UniversalCoverageFile> parseHtmlReport(String payload) {
        Map<String, String> files = splitCoverageFiles(payload);
        List<UniversalCoverageFile> result = new ArrayList<>();
        files.forEach((path, content) -> {
            if (!path.toLowerCase().endsWith(".html") || !content.contains("id=\"L")) return;
            UniversalCoverageFile file = new UniversalCoverageFile(SourceType.JAVA, sourcePathFromHtml(path));
            Matcher matcher = SOURCE_LINE_PATTERN.matcher(content);
            while (matcher.find()) {
                int line = integer(matcher.group(1));
                String classes = matcher.group(2);
                if (classes.contains("nc")) continue;
                boolean missed = classes.contains("pc") || classes.contains("bpc") || classes.contains("bfc");
                boolean covered = classes.contains("fc") || classes.contains("pc") || classes.contains("fc bfc");
                file.getLines().add(new UniversalCoverageFile.LineCoverage(line, covered ? 1 : 0));
                if (classes.contains("pc") || classes.contains("bpc") || classes.contains("bfc")) {
                    file.getBranches().add(new UniversalCoverageFile.BranchCoverage(line, 0, covered && !missed ? 1 : 0, "jacoco-html"));
                }
            }
            if (!file.getLines().isEmpty()) result.add(file);
        });
        return result;
    }

    private Map<String, String> splitCoverageFiles(String payload) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = COVERAGE_FILE_PATTERN.matcher(payload);
        String currentPath = null;
        int currentStart = -1;
        while (matcher.find()) {
            if (currentPath != null) {
                result.put(currentPath, payload.substring(currentStart, matcher.start()));
            }
            currentPath = matcher.group(1).trim();
            currentStart = matcher.end();
        }
        if (currentPath != null) {
            result.put(currentPath, payload.substring(currentStart));
        }
        return result;
    }

    private String sourcePathFromHtml(String htmlPath) {
        String path = htmlPath.replace('\\', '/');
        if (path.endsWith(".java.html")) path = path.substring(0, path.length() - 5);
        else if (path.endsWith(".html")) path = path.substring(0, path.length() - 5);
        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            String prefix = path.substring(0, slash).replace('.', '/');
            return prefix + "/" + path.substring(slash + 1);
        }
        return path;
    }

    private EntityResolver emptyEntityResolver() {
        return (publicId, systemId) -> new InputSource(new StringReader(""));
    }

    private void applyCounterFallbacks(UniversalCoverageFile file, Element sourceFile) {
        Element lineCounter = counter(sourceFile, "LINE");
        if (lineCounter != null && file.getLines().isEmpty()) {
            int missed = integer(lineCounter.getAttribute("missed"));
            int covered = integer(lineCounter.getAttribute("covered"));
            for (int line = 1; line <= missed + covered; line++) {
                file.getLines().add(new UniversalCoverageFile.LineCoverage(line, line <= covered ? 1 : 0));
            }
        }
        Element branchCounter = counter(sourceFile, "BRANCH");
        if (branchCounter != null && file.getBranches().isEmpty()) {
            int missed = integer(branchCounter.getAttribute("missed"));
            int covered = integer(branchCounter.getAttribute("covered"));
            for (int branch = 0; branch < missed + covered; branch++) {
                file.getBranches().add(new UniversalCoverageFile.BranchCoverage(1, branch, branch < covered ? 1 : 0, "jacoco"));
            }
        }
    }

    private void addMethodCoverage(UniversalCoverageFile file, Element packageNode, String sourceFileName) {
        NodeList classes = packageNode.getElementsByTagName("class");
        List<JacocoMethod> methodsForFile = new ArrayList<>();
        for (int classIndex = 0; classIndex < classes.getLength(); classIndex++) {
            Element classNode = (Element) classes.item(classIndex);
            if (!sourceFileName.equals(classNode.getAttribute("sourcefilename"))) continue;
            NodeList methods = classNode.getElementsByTagName("method");
            for (int methodIndex = 0; methodIndex < methods.getLength(); methodIndex++) {
                Element method = (Element) methods.item(methodIndex);
                int line = integer(method.getAttribute("line"));
                if (line > 0) methodsForFile.add(new JacocoMethod(classNode.getAttribute("name"), method, line));
            }
        }
        methodsForFile.sort(Comparator.comparingInt(JacocoMethod::line));
        int lastFileLine = file.getLines().stream().mapToInt(UniversalCoverageFile.LineCoverage::getLine).max().orElse(0);
        for (int methodIndex = 0; methodIndex < methodsForFile.size(); methodIndex++) {
            JacocoMethod item = methodsForFile.get(methodIndex);
            int nextLine = methodIndex + 1 < methodsForFile.size() ? methodsForFile.get(methodIndex + 1).line() : lastFileLine + 1;
            Element methodCounter = counter(item.element(), "METHOD");
            int covered = methodCounter == null ? 0 : integer(methodCounter.getAttribute("covered"));
            file.getFunctions().add(new UniversalCoverageFile.FunctionCoverage(
                    item.className(), item.element().getAttribute("name"), item.element().getAttribute("desc"),
                    item.line(), Math.max(item.line(), nextLine - 1), covered));
        }
    }

    private record JacocoMethod(String className, Element element, int line) {}

    private Element counter(Element parent, String type) {
        NodeList counters = parent.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element counter = (Element) counters.item(i);
            if (type.equals(counter.getAttribute("type"))) return counter;
        }
        return null;
    }

    private int integer(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; } }
}
