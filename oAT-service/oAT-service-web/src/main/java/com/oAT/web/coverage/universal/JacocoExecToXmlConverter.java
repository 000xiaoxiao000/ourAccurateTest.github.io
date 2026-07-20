package com.oAT.web.coverage.universal;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.MultiSourceFileLocator;
import org.jacoco.report.xml.XMLFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

@Component
public class JacocoExecToXmlConverter {
    private static final String REPORT_NAME = "JaCoCo exec coverage";

    public String convert(File execFile, List<File> classRoots, List<File> sourceRoots) throws IOException {
        Assert.notNull(execFile, "JaCoCo exec 文件不能为空");
        Assert.isTrue(execFile.isFile(), "JaCoCo exec 文件不存在: " + execFile);
        Assert.notEmpty(classRoots, "JaCoCo exec 转 XML 需要包含 classfiles，例如 zip 中的 classes/ 或 target/classes/");

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(execFile);

        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverageBuilder);
        Set<String> analyzedClasses = new HashSet<>();
        for (File classRoot : classRoots) {
            if (classRoot != null && classRoot.exists()) analyzeClassRoot(analyzer, classRoot, analyzedClasses);
        }

        IBundleCoverage bundle = coverageBuilder.getBundle(REPORT_NAME);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XMLFormatter formatter = new XMLFormatter();
        IReportVisitor visitor = formatter.createVisitor(output);
        visitor.visitInfo(loader.getSessionInfoStore().getInfos(), loader.getExecutionDataStore().getContents());
        visitor.visitBundle(bundle, sourceLocator(sourceRoots));
        visitor.visitEnd();
        return output.toString(StandardCharsets.UTF_8);
    }

    private void analyzeClassRoot(Analyzer analyzer, File classRoot, Set<String> analyzedClasses) throws IOException {
        if (classRoot.isDirectory()) {
            analyzeDirectory(analyzer, classRoot.toPath(), analyzedClasses);
        } else if (isJavaArchive(classRoot.toPath())) {
            analyzeJavaArchive(analyzer, classRoot, analyzedClasses);
        } else if (isClassFile(classRoot.toPath())) {
            analyzeClassFile(analyzer, classRoot.toPath(), classRoot.getName(), analyzedClasses);
        }
    }

    private void analyzeDirectory(Analyzer analyzer, Path root, Set<String> analyzedClasses) throws IOException {
        List<Path> classFiles = listFiles(root, this::isClassFile);
        for (Path classFile : classFiles) {
            String location = normalizeClassLocation(root.relativize(classFile).toString());
            analyzeClassFile(analyzer, classFile, location, analyzedClasses);
        }
    }

    private void analyzeJavaArchive(Analyzer analyzer, File archiveFile, Set<String> analyzedClasses) throws IOException {
        if (isNestedDependencyArchive(archiveFile.toPath())) return;
        try (ZipFile zip = new ZipFile(archiveFile, StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> classEntries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> isClassEntry(entry.getName()))
                    .filter(entry -> !isMultiReleaseClassEntry(entry.getName()))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : classEntries) {
                String location = normalizeClassLocation(entry.getName());
                if (!analyzedClasses.add(location)) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    analyzer.analyzeClass(input, archiveFile.getPath() + "@" + entry.getName());
                }
            }
        } catch (ZipException ignored) {
            // Some uploaded archives contain files ending in .jar that are not valid zip/jar files.
        }
    }

    private void analyzeClassFile(Analyzer analyzer, Path classFile, String location, Set<String> analyzedClasses) throws IOException {
        location = normalizeClassLocation(location);
        if (!analyzedClasses.add(location)) return;
        try (InputStream input = Files.newInputStream(classFile)) {
            analyzer.analyzeClass(input, classFile.toString());
        }
    }

    private boolean isClassFile(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".class");
    }

    private boolean isJavaArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".war");
    }

    private boolean isClassEntry(String name) {
        return name.toLowerCase().endsWith(".class");
    }

    private boolean isMultiReleaseClassEntry(String name) {
        return name.startsWith("META-INF/versions/");
    }

    private boolean isNestedDependencyArchive(Path path) {
        String normalized = normalizeClassLocation(path.toString());
        String lower = normalized.toLowerCase();
        return isJavaArchive(path) && (lower.contains("/boot-inf/lib/") || lower.contains("/web-inf/lib/"));
    }

    private List<Path> listFiles(Path root, PathMatcher matcher) throws IOException {
        List<Path> result = new ArrayList<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Path current = pending.removeFirst();
            if (Files.isDirectory(current)) {
                try (var entries = Files.newDirectoryStream(current)) {
                    for (Path entry : entries) pending.addLast(entry);
                }
            } else if (Files.isRegularFile(current) && matcher.matches(current)) {
                result.add(current);
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    @FunctionalInterface
    private interface PathMatcher {
        boolean matches(Path path);
    }

    private String normalizeClassLocation(String location) {
        return location.replace(File.separatorChar, '/');
    }

    private MultiSourceFileLocator sourceLocator(List<File> sourceRoots) {
        MultiSourceFileLocator locator = new MultiSourceFileLocator(4);
        if (sourceRoots != null) {
            for (File sourceRoot : sourceRoots) {
                if (sourceRoot != null && sourceRoot.exists()) {
                    locator.add(new DirectorySourceFileLocator(sourceRoot, StandardCharsets.UTF_8.name(), 4));
                }
            }
        }
        return locator;
    }
}
