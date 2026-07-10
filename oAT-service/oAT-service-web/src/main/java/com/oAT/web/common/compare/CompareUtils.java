package com.oAT.web.common.compare;

import com.oAT.web.common.Job;
import org.apache.commons.lang3.ArrayUtils;
import org.objectweb.asm.ClassReader;
import org.springframework.util.Assert;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CompareUtils {
    private Job.JobLogger logger;
    /**
     * total 当前阶段总任务数
     * loaded 当前阶段已完成数
     * 第一阶段：解压获取源文件25%
     * 第二阶段：解压获取目标文件25%
     * 第三阶段：比较文件50%
     */
    private Job.JobProgress progress;

    public CompareUtils(Job.JobLogger logger, Job.JobProgress progress) {
        this.logger = logger;
        this.progress = progress;
    }

    public List<CompareResult> compareJar(File sourceJar, File targetJar) throws IOException {
        ZipFile sourceFile = new ZipFile(sourceJar);
        ZipFile targetFile = new ZipFile(targetJar);

        List<ZipEntryWrapper> sourceEntrys = getZipEntryFiles(sourceFile, "*.class", "package-info.class");
        List<ZipEntryWrapper> targetEntrys = getZipEntryFiles(targetFile, "*.class", "package-info.class");
        return _compare(sourceEntrys, targetEntrys);
    }

    public List<CompareResult> compareWar(String packageName, File sourceWar, File targetWar) throws IOException {
        logger.info("开始比较版本...");
        logger.info(String.format("源文件：%s", sourceWar.toString()));
        logger.info(String.format("目标文件：%s", targetWar.toString()));
        logger.info(String.format("比较范围：%s", packageName));
        Assert.isTrue(sourceWar.exists(), "比对失败，源文件不存在");
        Assert.isTrue(targetWar.exists(), "比对失败，目标文件不存在");

        List<File> tempFiles = new ArrayList<>();
        String includePackageExpr = packageName.replaceAll("[.]", "/") + ".class";
        String includeExpr = includePackageExpr;
        includeExpr += "&BOOT-INF/classes/" + includePackageExpr;
        includeExpr += "&WEB-INF/classes/" + includePackageExpr;
        try {
            progress.next(String.format("正在扫描源文件：%s", sourceWar.getName()), 20);
            logger.info(String.format("扫描源文件：%s", sourceWar.toString()));
            List<ZipEntryWrapper> sourceClass = _getWarEntryFiles(sourceWar, includeExpr, tempFiles);
            logger.info(String.format("扫描源文件完成，比对总数：%s", sourceClass.size()));
            progress.next(String.format("正在扫描目标文件:%s", targetWar.getName()), 20);
            logger.info(String.format("扫描目标文件：%s", targetWar.toString()));
            List<ZipEntryWrapper> targetClass = _getWarEntryFiles(targetWar, includeExpr, tempFiles);
            logger.info(String.format("扫描目标文件完成，比对总数：%s", targetClass.size()));
            progress.next("正在比对文件差异..", 50);
            return _compare(sourceClass, targetClass);
        } finally {
            // 删除临时文件
            progress.next("正在删除临时文件", 10);
            progress.total = tempFiles.size();
            logger.info(String.format("开始删除临时文件，总数: %s", tempFiles.size()));
            for (File tempFile : tempFiles) {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                progress.loaded++;
            }
            logger.info("删除临时文件完成");
            progress.next("文件比较完成", 0);
        }
    }

    private List<CompareResult> _compare(List<ZipEntryWrapper> sourceEntrys, List<ZipEntryWrapper> targetEntrys) throws IOException {
        logger.info("开始比对文件差异");
        List<CompareResult> results = new ArrayList<>();
        progress.total = sourceEntrys.size();
        for (ZipEntryWrapper sourceEntry : sourceEntrys.toArray(new ZipEntryWrapper[0])) {
            for (ZipEntryWrapper targetEntry : targetEntrys.toArray(new ZipEntryWrapper[0])) {
                if (sourceEntry.getName().equals(targetEntry.getName())) {
                    sourceEntrys.remove(sourceEntry);
                    targetEntrys.remove(targetEntry);
                    CompareResult r = compare(toClassName(sourceEntry.getName()),
                            sourceEntry.getContent(),
                            targetEntry.getContent());
                    if (r.getModel() != CompareResult.Model.same) {
                        logger.info("发现【修改】类：" + r.getClassName());
                        results.add(r);
                    }
                }
            }
            progress.loaded++;
        }
        for (ZipEntryWrapper sourceEntry : sourceEntrys) {
            CompareResult r = new CompareResult(toClassName(sourceEntry.getName()));
            r.setModel(CompareResult.Model.add);
            try {
                CompareClass sourceClass = buildClass(sourceEntry.getContent());
                for (CompareMethod method : sourceClass.getMethods()) {
                    String desc = method.getDesc();
                    if (method.getFirstLine() != -1 && method.getLastLine() != -1) {
                        desc = String.format("行: %d-%d", method.getFirstLine(), method.getLastLine());
                    }
                    r.add(method.getName(), desc, CompareResult.Model.add);
                }
            } catch (Exception e) {
                logger.info("解析新增类方法失败: " + r.getClassName());
                logger.error(e);
            }
            logger.info("发现【新增】类：" + r.getClassName());
            results.add(r);
        }
        for (ZipEntryWrapper targetEntry : targetEntrys) {
            CompareResult r = new CompareResult(toClassName(targetEntry.getName()));
            r.setModel(CompareResult.Model.delete);
            try {
                CompareClass targetClass = buildClass(targetEntry.getContent());
                for (CompareMethod method : targetClass.getMethods()) {
                    String desc = method.getDesc();
                    if (method.getFirstLine() != -1 && method.getLastLine() != -1) {
                        desc = String.format("行: %d-%d", method.getFirstLine(), method.getLastLine());
                    }
                    r.add(method.getName(), desc, CompareResult.Model.delete);
                }
            } catch (Exception e) {
                logger.info("解析删除类方法失败: " + r.getClassName());
                logger.error(e);
            }
            logger.info("发现【删除】类：" + r.getClassName());
            results.add(r);
        }
        return results;
    }

    private List<ZipEntryWrapper> _getWarEntryFiles(File warFile, String includeExpr, List<File> tempFiles) throws IOException {
        Map<String, ZipEntryWrapper> wrapperMap = new HashMap<>();
        ZipFile sourceZip = new ZipFile(warFile);
        // 获取War 自身的class
        progress.total = 50;
        List<ZipEntryWrapper> selfList = getZipEntryFiles(sourceZip, includeExpr, null);
        for (ZipEntryWrapper zipEntryWrapper : selfList) {
            if (!wrapperMap.containsKey(zipEntryWrapper)) {
                wrapperMap.put(zipEntryWrapper.getName(), zipEntryWrapper);
            }
        }
        logger.info(String.format("扫描war包中的class文件完成，比对class总数:%s", selfList.size()));
        progress.loaded = 1;
        // 获取war 中的jar 包
        List<ZipEntryWrapper> jars = getZipEntryFiles(sourceZip, "*.jar", null);
        logger.info(String.format("扫描war包中的JAR包完成，JAR总数:%s", jars.size()));
        progress.total = jars.size() + 1;
        for (ZipEntryWrapper jar : jars) {
            // 写入临时文件
            Path path = Files.createTempFile("compare_cache", null);
            Files.write(path, jar.getContent());
            tempFiles.add(path.toFile());
            List<ZipEntryWrapper> list = getZipEntryFiles(new ZipFile(path.toFile()),
                    includeExpr, "package-info.class");
            for (ZipEntryWrapper zipEntryWrapper : list) {
                // 基于class name去重
                if (!wrapperMap.containsKey(zipEntryWrapper.getName())) {
                    wrapperMap.put(zipEntryWrapper.getName(), zipEntryWrapper);
                }
            }
            logger.info(String.format("扫描jar包中的class文件完成 %s，比对class总数:%s", jar.getName(), list.size()));
            progress.loaded++;
        }
        // 添加至返回结果
        List<ZipEntryWrapper> result = new ArrayList<>();
        result.addAll(wrapperMap.values());
        return result;
    }

    /**
     * @param classFile
     * @return 示例/com/xxx/xx/Hello
     */
    private String toClassName(String classFile) {
        String result = classFile.replaceAll("BOOT-INF/classes/", "");
        result = result.replaceAll("WEB-INF/classes/", "");
        result = result.replaceAll(".class", "");
//        result=result.replaceAll("/",".");
        return result;
    }

    private class ZipEntryWrapper {
        ZipEntry entry;
        ZipFile zipFile;

        public ZipEntryWrapper(ZipFile zipFile, ZipEntry entry) {
            this.entry = entry;
            this.zipFile = zipFile;
        }

        private String getName() {
            return entry.getName();
        }

        private byte[] getContent() throws IOException {
            try (InputStream input = zipFile.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private List<ZipEntryWrapper> getZipEntryFiles(ZipFile zipFile, String includeExpr, String excludeExpr) {
        List<ZipEntryWrapper> result = new ArrayList<>(zipFile.size());
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        ZipEntry entry;
        WildcardMatcher include = new WildcardMatcher(includeExpr == null ? "*" : includeExpr);
        WildcardMatcher exclude = new WildcardMatcher(excludeExpr == null ? "" : excludeExpr);

        while (entries.hasMoreElements()) {
            entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            if (!include.matches(entry.getName())) {
                continue;
            }
            if (exclude.matches(entry.getName())) {
                continue;
            }
            result.add(new ZipEntryWrapper(zipFile, entry));
        }
        return result;
    }

    public CompareResult compare(String className, InputStream source, InputStream target) throws IOException {
        byte[] s = source.readAllBytes();
        byte[] t = target.readAllBytes();
        return compare(className, s, t);
    }

    public CompareResult compare(String className, byte[] sources, byte[] target) {
        if (Arrays.equals(sources, target)) {
            //没有任何修改
            return new CompareResult(className, CompareResult.Model.same);
        }
        CompareClass sourceClass = buildClass(sources);
        CompareClass targetClass = buildClass(target);
        CompareResult result = new CompareResult(className);
        for (CompareMethod sMethod : sourceClass.getMethods().toArray(new CompareMethod[0])) {
            for (CompareMethod tMethod : targetClass.getMethods().toArray(new CompareMethod[0])) {
                if (!sMethod.getName().equals(tMethod.getName())) {
                    continue;
                }
                if (!sMethod.getDesc().equals(tMethod.getDesc())) {
                    continue;
                }

                //表示双方都存在该方法
                sourceClass.getMethods().remove(sMethod);
                targetClass.getMethods().remove(tMethod);
                if (!sMethod.getBody().equals(tMethod.getBody())) {
                    String desc = sMethod.getDesc();
                    if (sMethod.getFirstLine() != -1 && sMethod.getLastLine() != -1) {
                        desc = String.format("行: %d-%d", sMethod.getFirstLine(), sMethod.getLastLine());
                    }
                    result.add(sMethod.getName(), desc, CompareResult.Model.update);
                    logger.info(String.format("发现【变更】方法%s#%s：", className, sMethod.getName()));
                }
            }
        }
        //新版本(源)比较旧版本(目标)
        for (CompareMethod method : sourceClass.getMethods()) {
            String desc = method.getDesc();
            if (method.getFirstLine() != -1 && method.getLastLine() != -1) {
                desc = String.format("行: %d-%d", method.getFirstLine(), method.getLastLine());
            }
            result.add(method.getName(), desc, CompareResult.Model.add);
            logger.info(String.format("发现【新增】方法%s#%s：", className, method.getName()));
        }
        //新版本(源)比较旧版本(目标)
        for (CompareMethod method : targetClass.getMethods()) {
            String desc = method.getDesc();
            if (method.getFirstLine() != -1 && method.getLastLine() != -1) {
                desc = String.format("行: %d-%d", method.getFirstLine(), method.getLastLine());
            }
            result.add(method.getName(), desc, CompareResult.Model.delete);
            logger.info(String.format("发现【删除】方法%s#%s：", className, method.getName()));
        }
        if (ArrayUtils.isEmpty(result.getMethods())) {
            result.setModel(CompareResult.Model.same);
        } else {
            result.setModel(CompareResult.Model.update);
        }

        return result;
    }

    private CompareClass buildClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        TraceClassVisitor visitor = new TraceClassVisitor();
        reader.accept(visitor, ClassReader.SKIP_FRAMES);
        return visitor.getCompareClass();
    }

}
