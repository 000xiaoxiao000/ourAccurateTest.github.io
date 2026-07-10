package com.oAT.web.common;

import com.oAT.web.common.compare.WildcardMatcher;
import org.springframework.util.Assert;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipFileVisit implements Closeable{

    private final List<ZipEntryWrapper> entryFiles;
    private final ZipFile zipFile;
    private final List<Runnable> distorys = new ArrayList<>();

    public ZipFileVisit(String filePath) throws IOException {
        zipFile = new ZipFile(new File(filePath));
        entryFiles = getZipEntryFiles(zipFile, null, null);
    }


    public Stream<ZipEntryWrapper> streams() {
        return entryFiles.stream();
    }

    public Stream<ZipEntryWrapper> streams(String type) {
        return entryFiles.stream().filter(wildcardMatcherFilter("*." + type, null));
    }

    public Stream<ZipEntryWrapper> streamAndSub(String type, String subType) {
        subType = subType.toLowerCase();
        Assert.isTrue(Arrays.asList("zip", "jar").contains(subType), "subType must be zip|jar");

        Stream<ZipEntryWrapper> subStream = streams(subType).flatMap(a -> {
            ZipFileVisit visit;
            try {
                Path path = Files.createTempFile("Zip_File_Visit", null);
                Files.write(path, a.getContent());
                visit = new ZipFileVisit(path.toFile().getPath());
                distorys.add(() -> {
                    try {
                        visit.close();
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return visit.streams(type);
        });
        return Stream.concat(streams(type), subStream);
    }


    private List<ZipEntryWrapper> getZipEntryFiles(ZipFile zipFile, String includeExpr, String excludeExpr) {
        List<ZipEntryWrapper> result = new ArrayList<>(zipFile.size());
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        ZipEntry entry;
        while (entries.hasMoreElements()) {
            entry = entries.nextElement();
            if (!entry.isDirectory()) {
                result.add(new ZipEntryWrapper(entry));
            }
        }
        return result.stream().filter(wildcardMatcherFilter(includeExpr, excludeExpr)).collect(Collectors.toList());
    }

    public static Predicate<ZipEntryWrapper> wildcardMatcherFilter(String includeExpr, String excludeExpr) {
        WildcardMatcher include = new WildcardMatcher(includeExpr == null ? "*" : includeExpr);
        WildcardMatcher exclude = new WildcardMatcher(excludeExpr == null ? "" : excludeExpr);
        return a -> include.matches(a.getName()) && !exclude.matches(a.getName());
    }

    @Override
    public void close() throws IOException {
        distorys.forEach(Runnable::run);
        zipFile.close();
    }


    public class ZipEntryWrapper {
        ZipEntry entry;

        public ZipEntryWrapper(ZipEntry entry) {
            this.entry = entry;
        }

        public String getName() {
            return entry.getName();
        }

        public byte[] getContent() throws IOException {
            try (InputStream input = zipFile.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

}
