package com.oAT.web.coverage.universal;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
    private static final Pattern SOURCE_LINE_PATTERN = Pattern.compile("<span\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_ID_PATTERN = Pattern.compile("\\bid=\"L(\\d+)\"");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass=\"([^\"]*)\"");
    private static final Pattern BRANCH_TITLE_PATTERN = Pattern.compile("\\btitle=\"(\\d+)\\s+of\\s+(\\d+)\\s+branches?\\s+missed\\.?\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_BRANCHES_PATTERN = Pattern.compile("\\btitle=\"All\\s+(\\d+)\\s+branches?\\s+(missed|covered)\\.?\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TFOOT_PATTERN = Pattern.compile("<tfoot>\\s*<tr>(.*?)</tr>\\s*</tfoot>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL_PATTERN = Pattern.compile("<td(?:\\s[^>]*)?>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MISSED_OF_TOTAL_PATTERN = Pattern.compile("([\\d,]+)\\s+of\\s+([\\d,]+)", Pattern.CASE_INSENSITIVE);

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
        if (text.toLowerCase(java.util.Locale.ROOT).contains("<html")) {
            List<UniversalCoverageFile> htmlFiles = parseStandaloneHtmlReport(text);
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
                    applyReportCounters(file, packageNode, sourceFile.getAttribute("name"));
                    result.add(file);
                }
            }
            return result;
        } catch (Exception exception) {
            List<UniversalCoverageFile> htmlFiles = parseHtmlReport(text);
            if (!htmlFiles.isEmpty()) return htmlFiles;
            htmlFiles = parseStandaloneHtmlReport(text);
            if (!htmlFiles.isEmpty()) return htmlFiles;
            throw new IllegalArgumentException("无法解析 JaCoCo XML 覆盖率报告", exception);
        }
    }

    private List<UniversalCoverageFile> parseStandaloneHtmlReport(String html) {
        List<String> cells = footerCells(html);
        if (cells.size() < 13 || !"Total".equalsIgnoreCase(cells.get(0))) return List.of();
        UniversalCoverageFile summary = new UniversalCoverageFile(SourceType.JAVA, "__jacoco_report_total__.java");
        int[] branches = missedAndTotal(cells.get(3));
        summary.setReportCoveredBranches(Math.max(branches[1] - branches[0], 0));
        summary.setReportTotalBranches(branches[1]);
        summary.setReportTotalComplexity(number(cells.get(6)));
        summary.setReportCoveredLines(Math.max(number(cells.get(8)) - number(cells.get(7)), 0));
        summary.setReportTotalLines(number(cells.get(8)));
        summary.setReportCoveredMethods(Math.max(number(cells.get(10)) - number(cells.get(9)), 0));
        summary.setReportTotalMethods(number(cells.get(10)));
        summary.setReportCoveredClasses(Math.max(number(cells.get(12)) - number(cells.get(11)), 0));
        summary.setReportTotalClasses(number(cells.get(12)));
        return List.of(summary);
    }

    private List<UniversalCoverageFile> parseHtmlReport(String payload) {
        Map<String, String> files = splitCoverageFiles(payload);
        List<UniversalCoverageFile> result = new ArrayList<>();
        files.forEach((path, content) -> {
            if (!path.toLowerCase().endsWith(".html") || !content.contains("id=\"L")) return;
            UniversalCoverageFile file = new UniversalCoverageFile(SourceType.JAVA, sourcePathFromHtml(path));
            Matcher matcher = SOURCE_LINE_PATTERN.matcher(content);
            while (matcher.find()) {
                String attributes = matcher.group(1);
                Matcher lineId = LINE_ID_PATTERN.matcher(attributes);
                Matcher classAttribute = CLASS_PATTERN.matcher(attributes);
                if (!lineId.find() || !classAttribute.find()) continue;
                int line = integer(lineId.group(1));
                String classes = classAttribute.group(1);
                boolean covered = containsCssClass(classes, "fc") || containsCssClass(classes, "pc");
                boolean missed = containsCssClass(classes, "nc");
                if (!covered && !missed) continue;
                file.getLines().add(new UniversalCoverageFile.LineCoverage(line, covered ? 1 : 0));
                int[] branchCounts = branchCounts(attributes);
                if (branchCounts[1] > 0) {
                    int missedBranches = branchCounts[0];
                    int totalBranches = branchCounts[1];
                    int coveredBranches = Math.max(totalBranches - missedBranches, 0);
                    for (int branch = 0; branch < totalBranches; branch++) {
                        file.getBranches().add(new UniversalCoverageFile.BranchCoverage(
                                line, branch, branch < coveredBranches ? 1 : 0, "jacoco-html"));
                    }
                }
            }
            if (!file.getLines().isEmpty()) result.add(file);
        });
        applyHtmlReportTotals(files, result);
        return result;
    }

    private void applyHtmlReportTotals(Map<String, String> files, List<UniversalCoverageFile> result) {
        if (result.isEmpty()) return;
        Map.Entry<String, String> rootIndex = files.entrySet().stream()
                .filter(entry -> entry.getKey().replace('\\', '/').toLowerCase().endsWith("index.html"))
                .filter(entry -> !entry.getKey().toLowerCase().endsWith("index.source.html"))
                .min(Comparator.comparingInt(entry -> pathDepth(entry.getKey())))
                .orElse(null);
        if (rootIndex == null) return;
        List<String> cells = footerCells(rootIndex.getValue());
        // JaCoCo report columns: element, instructions, %, branches, %, missed/total complexity,
        // missed/total lines, missed/total methods, missed/total classes.
        if (cells.size() < 13 || !"Total".equalsIgnoreCase(cells.get(0))) return;
        UniversalCoverageFile summary = result.get(0);
        int[] branches = missedAndTotal(cells.get(3));
        summary.setReportCoveredBranches(Math.max(branches[1] - branches[0], 0));
        summary.setReportTotalBranches(branches[1]);
        summary.setReportTotalComplexity(number(cells.get(6)));
        summary.setReportCoveredLines(Math.max(number(cells.get(8)) - number(cells.get(7)), 0));
        summary.setReportTotalLines(number(cells.get(8)));
        summary.setReportCoveredMethods(Math.max(number(cells.get(10)) - number(cells.get(9)), 0));
        summary.setReportTotalMethods(number(cells.get(10)));
        summary.setReportCoveredClasses(Math.max(number(cells.get(12)) - number(cells.get(11)), 0));
        summary.setReportTotalClasses(number(cells.get(12)));
    }

    private List<String> footerCells(String html) {
        Matcher footer = TFOOT_PATTERN.matcher(html);
        if (!footer.find()) return List.of();
        List<String> result = new ArrayList<>();
        Matcher cell = CELL_PATTERN.matcher(footer.group(1));
        while (cell.find()) result.add(cell.group(1).replaceAll("<[^>]+>", "").trim());
        return result;
    }

    private int[] missedAndTotal(String text) {
        Matcher matcher = MISSED_OF_TOTAL_PATTERN.matcher(text);
        return matcher.find() ? new int[]{number(matcher.group(1)), number(matcher.group(2))} : new int[]{0, 0};
    }

    private int[] branchCounts(String attributes) {
        Matcher partial = BRANCH_TITLE_PATTERN.matcher(attributes);
        if (partial.find()) return new int[]{integer(partial.group(1)), integer(partial.group(2))};
        Matcher all = ALL_BRANCHES_PATTERN.matcher(attributes);
        if (!all.find()) return new int[]{0, 0};
        int total = integer(all.group(1));
        return new int[]{"missed".equalsIgnoreCase(all.group(2)) ? total : 0, total};
    }

    private int number(String value) {
        return integer(value == null ? "" : value.replace(",", "").trim());
    }

    private int pathDepth(String path) {
        return path.replace('\\', '/').split("/").length;
    }

    private boolean containsCssClass(String classes, String expected) {
        for (String cssClass : classes.split("\\s+")) if (expected.equals(cssClass)) return true;
        return false;
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

    private void applyReportCounters(UniversalCoverageFile file, Element packageNode, String sourceFileName) {
        NodeList classes = packageNode.getElementsByTagName("class");
        int totalClasses = 0;
        int coveredClasses = 0;
        int totalMethods = 0;
        int coveredMethods = 0;
        int totalComplexity = 0;
        for (int classIndex = 0; classIndex < classes.getLength(); classIndex++) {
            Element classNode = (Element) classes.item(classIndex);
            if (!sourceFileName.equals(classNode.getAttribute("sourcefilename"))) continue;
            totalClasses++;
            Element classCounter = directCounter(classNode, "CLASS");
            if (classCounter != null) {
                coveredClasses += integer(classCounter.getAttribute("covered"));
            } else if (hasCoveredMethod(classNode)) {
                // Older/minimal JaCoCo XML can omit the class counter.
                coveredClasses++;
            }
            Element methodCounter = directCounter(classNode, "METHOD");
            if (methodCounter != null) {
                totalMethods += counterTotal(methodCounter);
                coveredMethods += integer(methodCounter.getAttribute("covered"));
            } else {
                NodeList methods = classNode.getElementsByTagName("method");
                totalMethods += methods.getLength();
                for (int methodIndex = 0; methodIndex < methods.getLength(); methodIndex++) {
                    Element methodCounterFallback = directCounter((Element) methods.item(methodIndex), "METHOD");
                    if (methodCounterFallback != null && integer(methodCounterFallback.getAttribute("covered")) > 0) {
                        coveredMethods++;
                    }
                }
            }
            Element complexityCounter = directCounter(classNode, "COMPLEXITY");
            if (complexityCounter != null) totalComplexity += counterTotal(complexityCounter);
        }
        file.setReportTotalClasses(totalClasses);
        file.setReportCoveredClasses(coveredClasses);
        file.setReportTotalMethods(totalMethods);
        file.setReportCoveredMethods(coveredMethods);
        file.setReportTotalComplexity(totalComplexity);
    }

    private boolean hasCoveredMethod(Element classNode) {
        NodeList methods = classNode.getElementsByTagName("method");
        for (int methodIndex = 0; methodIndex < methods.getLength(); methodIndex++) {
            Element methodCounter = directCounter((Element) methods.item(methodIndex), "METHOD");
            if (methodCounter != null && integer(methodCounter.getAttribute("covered")) > 0) return true;
        }
        return false;
    }

    private record JacocoMethod(String className, Element element, int line) {}

    private Element counter(Element parent, String type) {
        return directCounter(parent, type);
    }

    private Element directCounter(Element parent, String type) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element counter
                    && "counter".equals(counter.getTagName())
                    && type.equals(counter.getAttribute("type"))) {
                return counter;
            }
        }
        return null;
    }

    private int counterTotal(Element counter) {
        return integer(counter.getAttribute("missed")) + integer(counter.getAttribute("covered"));
    }

    private int integer(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; } }
}
