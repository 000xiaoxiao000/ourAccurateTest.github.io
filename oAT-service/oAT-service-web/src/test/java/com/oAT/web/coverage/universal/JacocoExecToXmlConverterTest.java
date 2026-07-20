package com.oAT.web.coverage.universal;

import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JacocoExecToXmlConverterTest {
    @TempDir
    Path tempDir;

    @Test
    void convertsExecAndClassFilesToJacocoXml() throws Exception {
        File execFile = tempDir.resolve("jacoco.exec").toFile();
        try (FileOutputStream output = new FileOutputStream(execFile)) {
            new ExecutionDataWriter(output).visitSessionInfo(new SessionInfo("test", 0, 0));
        }

        String xml = new JacocoExecToXmlConverter().convert(execFile,
                List.of(new File("target/test-classes/com/oAT/web/coverage/universal/JacocoExecToXmlConverterTest.class")),
                List.of(new File("src/test/java")));

        assertTrue(xml.startsWith("<?xml"));
        assertTrue(xml.contains("<report name=\"JaCoCo exec coverage\""));
        assertTrue(xml.contains("JacocoExecToXmlConverterTest"));
    }

    @Test
    void ignoresNestedDependencyJarsInSpringBootJar() throws Exception {
        File execFile = tempDir.resolve("jacoco.exec").toFile();
        try (FileOutputStream output = new FileOutputStream(execFile)) {
            new ExecutionDataWriter(output).visitSessionInfo(new SessionInfo("test", 0, 0));
        }

        Path testClass = Path.of("target/test-classes/com/oAT/web/coverage/universal/JacocoExecToXmlConverterTest.class");
        Path dependencyJar = tempDir.resolve("dependency.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dependencyJar))) {
            zip.putNextEntry(new ZipEntry("com/oAT/web/coverage/universal/JacocoExecToXmlConverterTest.class"));
            Files.copy(testClass, zip);
            zip.closeEntry();
        }

        Path bootJar = tempDir.resolve("app.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bootJar))) {
            zip.putNextEntry(new ZipEntry("BOOT-INF/classes/com/oAT/web/coverage/universal/JacocoExecToXmlConverterTest.class"));
            Files.copy(testClass, zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("BOOT-INF/lib/dependency.jar"));
            Files.copy(dependencyJar, zip);
            zip.closeEntry();
        }

        String xml = new JacocoExecToXmlConverter().convert(execFile, List.of(bootJar.toFile()), List.of(new File("src/test/java")));

        assertTrue(xml.startsWith("<?xml"));
        assertTrue(xml.contains("JacocoExecToXmlConverterTest"));
    }

    @Test
    void analyzesWebInfClassesInWar() throws Exception {
        File execFile = tempDir.resolve("jacoco.exec").toFile();
        try (FileOutputStream output = new FileOutputStream(execFile)) {
            new ExecutionDataWriter(output).visitSessionInfo(new SessionInfo("test", 0, 0));
        }

        Path testClass = Path.of("target/test-classes/com/oAT/web/coverage/universal/JacocoExecToXmlConverterTest.class");
        Path dependencyJar = tempDir.resolve("dependency.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dependencyJar))) {
            zip.putNextEntry(new ZipEntry("com/oAT/web/coverage/universal/JacocoExecToXmlConverterTest.class"));
            Files.copy(testClass, zip);
            zip.closeEntry();
        }

        Path war = tempDir.resolve("app.war");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
            zip.putNextEntry(new ZipEntry("WEB-INF/classes/com/oAT/web/coverage/universal/JacocoExecToXmlConverterTest.class"));
            Files.copy(testClass, zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("WEB-INF/lib/dependency.jar"));
            Files.copy(dependencyJar, zip);
            zip.closeEntry();
        }

        String xml = new JacocoExecToXmlConverter().convert(execFile, List.of(war.toFile()), List.of(new File("src/test/java")));

        assertTrue(xml.startsWith("<?xml"));
        assertTrue(xml.contains("JacocoExecToXmlConverterTest"));
    }

    @Test
    void skipsInvalidJarFiles() throws Exception {
        File execFile = tempDir.resolve("jacoco.exec").toFile();
        try (FileOutputStream output = new FileOutputStream(execFile)) {
            new ExecutionDataWriter(output).visitSessionInfo(new SessionInfo("test", 0, 0));
        }
        Path invalidJar = tempDir.resolve("broken.jar");
        Files.writeString(invalidJar, "not a jar");

        String xml = new JacocoExecToXmlConverter().convert(execFile,
                List.of(new File("target/test-classes/com/oAT/web/coverage/universal/JacocoExecToXmlConverterTest.class"),
                        invalidJar.toFile()),
                List.of(new File("src/test/java")));

        assertTrue(xml.startsWith("<?xml"));
        assertTrue(xml.contains("JacocoExecToXmlConverterTest"));
    }
}
