package com.oAT.web.coverage.universal;

import com.oAT.web.common.EncryptUtil;
import com.oAT.web.esDao.entity.ClassCoverageIndex;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class UniversalCoverageFile implements Serializable {
    private SourceType sourceType;
    private String filePath;
    private List<FunctionCoverage> functions = new ArrayList<>();
    private List<LineCoverage> lines = new ArrayList<>();
    private List<BranchCoverage> branches = new ArrayList<>();
    private Map<Integer, List<ClassCoverageIndex.CoverageFootprintRecord>> lineFootprints = new LinkedHashMap<>();
    private Map<String, List<ClassCoverageIndex.CoverageFootprintRecord>> branchFootprints = new LinkedHashMap<>();

    public UniversalCoverageFile() {
    }

    public UniversalCoverageFile(SourceType sourceType, String filePath) {
        this.sourceType = sourceType;
        this.filePath = filePath;
    }

    public UniversalCoverageFile merge(UniversalCoverageFile other) {
        if (other == null) return this;
        lines = mergeBy(lines, other.lines, LineCoverage::getLine, LineCoverage::merge);
        functions = mergeBy(functions, other.functions, FunctionCoverage::key, FunctionCoverage::merge);
        branches = mergeBy(branches, other.branches, BranchCoverage::key, BranchCoverage::merge);
        mergeFootprints(lineFootprints, other.lineFootprints);
        mergeFootprints(branchFootprints, other.branchFootprints);
        return this;
    }

    public UniversalCoverageFile withFootprint(ClassCoverageIndex.CoverageFootprintRecord footprint) {
        if (footprint == null) return this;
        for (LineCoverage line : lines) {
            if (line.coveredCount > 0) lineFootprints.computeIfAbsent(line.line, key -> new ArrayList<>()).add(footprint);
        }
        for (BranchCoverage branch : branches) {
            if (branch.coveredCount > 0) branchFootprints.computeIfAbsent(branch.branchGroupKey(), key -> new ArrayList<>()).add(footprint);
        }
        return this;
    }

    public ClassCoverageIndex toClassCoverageIndex(String appId) {
        ClassCoverageIndex index = new ClassCoverageIndex();
        index.setId(stableId(appId));
        index.setAppId(appId);
        index.setClassName(filePath);
        index.setDisplayName(filePath);
        index.setSourcePath(filePath);
        index.setSourceType(sourceType == null ? null : sourceType.name());
        index.setLanguage(sourceType == null ? null : sourceType.name());
        index.setTotalLines(lines.size());
        index.setCoveredLines((int) lines.stream().filter(line -> line.coveredCount > 0).count());
        index.setLineRate(rate(index.getCoveredLines(), index.getTotalLines()));
        index.setTotalLineNumbers(lines.stream()
                .map(LineCoverage::getLine)
                .distinct()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new)));
        index.setCoveredLineNumbers(lines.stream()
                .filter(line -> line.coveredCount > 0)
                .map(LineCoverage::getLine)
                .distinct()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new)));
        index.setTotalBranchTargets(branches.size());
        index.setCoveredBranchTargets((int) branches.stream().filter(branch -> branch.coveredCount > 0).count());
        index.setTotalBranches((int) branches.stream().map(BranchCoverage::branchGroupKey).distinct().count());
        index.setCoveredBranches((int) branches.stream().filter(branch -> branch.coveredCount > 0)
                .map(BranchCoverage::branchGroupKey).distinct().count());
        index.setBranchRate(rate(index.getCoveredBranchTargets(), index.getTotalBranchTargets()));
        List<ClassCoverageIndex.MethodCoverageDetail> methods = functions.stream()
                .map(function -> function.toMethodCoverageDetail(lines))
                .peek(method -> {
                    method.setLineFootprints(lineFootprintsFor(method));
                    applyBranchesToMethod(method);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        if (methods.isEmpty()) {
            Set<Integer> totalLines = lines.stream().map(LineCoverage::getLine).collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Integer> coveredLines = lines.stream().filter(line -> line.coveredCount > 0)
                    .map(LineCoverage::getLine).collect(Collectors.toCollection(LinkedHashSet::new));
            ClassCoverageIndex.MethodCoverageDetail method = syntheticFileMethod(totalLines, coveredLines);
            applyBranchesToMethod(method);
            methods.add(method);
        }
        index.setMethods(methods);
        index.setTotalMethods(methods.size());
        index.setCoveredMethods((int) methods.stream().filter(ClassCoverageIndex.MethodCoverageDetail::isCovered).count());
        index.setMethodRate(rate(index.getCoveredMethods(), index.getTotalMethods()));
        return index;
    }

    public String stableId(String appId) {
        return EncryptUtil.MD5((appId == null ? "" : appId) + ":" + (filePath == null ? "" : filePath));
    }

    private Map<Integer, List<ClassCoverageIndex.CoverageFootprintRecord>> lineFootprintsFor(
            ClassCoverageIndex.MethodCoverageDetail method) {
        Map<Integer, List<ClassCoverageIndex.CoverageFootprintRecord>> result = new LinkedHashMap<>();
        for (Integer line : method.getCoveredLineNumbers()) {
            List<ClassCoverageIndex.CoverageFootprintRecord> records = lineFootprints.get(line);
            if (records != null) result.put(line, records);
        }
        return result;
    }

    private ClassCoverageIndex.MethodCoverageDetail syntheticFileMethod(Set<Integer> totalLines, Set<Integer> coveredLines) {
        ClassCoverageIndex.MethodCoverageDetail detail = new ClassCoverageIndex.MethodCoverageDetail();
        detail.setMethodName(filePath);
        detail.setMethodDesc("file");
        detail.setTotalLineNumbers(new ArrayList<>(totalLines));
        detail.setCoveredLineNumbers(new ArrayList<>(coveredLines));
        detail.setTotalLines(totalLines.size());
        detail.setCoveredLines(coveredLines.size());
        detail.setCovered(!coveredLines.isEmpty());
        return detail;
    }

    private void applyBranchesToMethod(ClassCoverageIndex.MethodCoverageDetail method) {
        Map<String, List<Integer>> total = new LinkedHashMap<>();
        Map<String, List<Integer>> covered = new LinkedHashMap<>();
        Map<String, List<ClassCoverageIndex.CoverageFootprintRecord>> footprints = new LinkedHashMap<>();
        Set<Integer> coveredLines = new LinkedHashSet<>();
        int start = method.getTotalLineNumbers().stream().mapToInt(Integer::intValue).min().orElse(Integer.MIN_VALUE);
        int end = method.getTotalLineNumbers().stream().mapToInt(Integer::intValue).max().orElse(Integer.MAX_VALUE);
        for (BranchCoverage branch : branches) {
            if (branch.line < start || branch.line > end) continue;
            String key = branch.branchGroupKey();
            total.computeIfAbsent(key, ignored -> new ArrayList<>()).add(branch.branchIndex);
            if (branch.coveredCount > 0) {
                covered.computeIfAbsent(key, ignored -> new ArrayList<>()).add(branch.branchIndex);
                coveredLines.add(branch.line);
                List<ClassCoverageIndex.CoverageFootprintRecord> records = branchFootprints.get(key);
                if (records != null) footprints.put(key, records);
            }
        }
        method.setTotalBranchTargetProbeMap(total);
        method.setCoveredBranchTargetProbeMap(covered);
        method.setBranchFootprints(footprints);
        method.setCoveredBranchLines(new ArrayList<>(coveredLines));
        method.setTotalBranchTargets(total.values().stream().mapToInt(List::size).sum());
        method.setCoveredBranchTargets(covered.values().stream().mapToInt(List::size).sum());
        method.setTotalBranches(total.size());
        method.setCoveredBranches(covered.size());
        method.setBranchRate(rate(method.getCoveredBranchTargets(), method.getTotalBranchTargets()));
    }

    private static <T, K> List<T> mergeBy(List<T> left, List<T> right, Function<T, K> key, Merger<T> merger) {
        Map<K, T> result = new LinkedHashMap<>();
        if (left != null) left.forEach(item -> result.put(key.apply(item), item));
        if (right != null) right.forEach(item -> result.merge(key.apply(item), item, merger::merge));
        return new ArrayList<>(result.values());
    }

    private <K> void mergeFootprints(Map<K, List<ClassCoverageIndex.CoverageFootprintRecord>> target,
                                     Map<K, List<ClassCoverageIndex.CoverageFootprintRecord>> source) {
        if (source == null) return;
        source.forEach((key, value) -> target.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(value));
    }

    private static Double rate(int covered, int total) {
        return total == 0 ? null : Math.round((double) covered / total * 10000) / 10000.0;
    }

    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public List<FunctionCoverage> getFunctions() { return functions; }
    public void setFunctions(List<FunctionCoverage> functions) { this.functions = functions; }
    public List<LineCoverage> getLines() { return lines; }
    public void setLines(List<LineCoverage> lines) { this.lines = lines; }
    public List<BranchCoverage> getBranches() { return branches; }
    public void setBranches(List<BranchCoverage> branches) { this.branches = branches; }
    public Map<Integer, List<ClassCoverageIndex.CoverageFootprintRecord>> getLineFootprints() { return lineFootprints; }
    public void setLineFootprints(Map<Integer, List<ClassCoverageIndex.CoverageFootprintRecord>> lineFootprints) { this.lineFootprints = lineFootprints; }
    public Map<String, List<ClassCoverageIndex.CoverageFootprintRecord>> getBranchFootprints() { return branchFootprints; }
    public void setBranchFootprints(Map<String, List<ClassCoverageIndex.CoverageFootprintRecord>> branchFootprints) { this.branchFootprints = branchFootprints; }

    interface Merger<T> { T merge(T left, T right); }

    public static class LineCoverage implements Serializable {
        private int line;
        private int coveredCount;
        public LineCoverage() {}
        public LineCoverage(int line, int coveredCount) { this.line = line; this.coveredCount = coveredCount; }
        LineCoverage merge(LineCoverage other) { return new LineCoverage(line, coveredCount + other.coveredCount); }
        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }
        public int getCoveredCount() { return coveredCount; }
        public void setCoveredCount(int coveredCount) { this.coveredCount = coveredCount; }
    }

    public static class FunctionCoverage implements Serializable {
        private String className;
        private String name;
        private String descriptor;
        private int startLine;
        private int endLine;
        private int coveredCount;
        public FunctionCoverage() {}
        public FunctionCoverage(String name, int startLine, int endLine, int coveredCount) {
            this.name = name; this.startLine = startLine; this.endLine = endLine; this.coveredCount = coveredCount;
        }
        public FunctionCoverage(String className, String name, String descriptor, int startLine, int endLine, int coveredCount) {
            this(name, startLine, endLine, coveredCount);
            this.className = className;
            this.descriptor = descriptor;
        }
        FunctionCoverage merge(FunctionCoverage other) {
            return new FunctionCoverage(className, name, descriptor, Math.min(startLine, other.startLine),
                    Math.max(endLine, other.endLine), coveredCount + other.coveredCount);
        }
        ClassCoverageIndex.MethodCoverageDetail toMethodCoverageDetail(List<LineCoverage> fileLines) {
            ClassCoverageIndex.MethodCoverageDetail detail = new ClassCoverageIndex.MethodCoverageDetail();
            detail.setClassName(className);
            detail.setMethodName(name);
            detail.setMethodDesc(descriptor == null ? name : descriptor);
            detail.setStartLine(startLine);
            List<Integer> total = fileLines.stream()
                    .filter(line -> line.line >= startLine && line.line <= endLine)
                    .map(LineCoverage::getLine).distinct().sorted().toList();
            List<Integer> covered = fileLines.stream()
                    .filter(line -> line.line >= startLine && line.line <= endLine && line.coveredCount > 0)
                    .map(LineCoverage::getLine).distinct().sorted().toList();
            detail.setTotalLineNumbers(total);
            detail.setTotalLines(total.size());
            detail.setCoveredLineNumbers(covered);
            detail.setCoveredLines(covered.size());
            detail.setCovered(!covered.isEmpty() || coveredCount > 0);
            return detail;
        }
        String key() { return className + ":" + name + ":" + descriptor + ":" + startLine + ":" + endLine; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescriptor() { return descriptor; }
        public void setDescriptor(String descriptor) { this.descriptor = descriptor; }
        public int getStartLine() { return startLine; }
        public void setStartLine(int startLine) { this.startLine = startLine; }
        public int getEndLine() { return endLine; }
        public void setEndLine(int endLine) { this.endLine = endLine; }
        public int getCoveredCount() { return coveredCount; }
        public void setCoveredCount(int coveredCount) { this.coveredCount = coveredCount; }
    }

    public static class BranchCoverage implements Serializable {
        private int line;
        private int branchIndex;
        private int coveredCount;
        private String groupId;
        public BranchCoverage() {}
        public BranchCoverage(int line, int branchIndex, int coveredCount, String groupId) {
            this.line = line; this.branchIndex = branchIndex; this.coveredCount = coveredCount; this.groupId = groupId;
        }
        BranchCoverage merge(BranchCoverage other) {
            return new BranchCoverage(line, branchIndex, coveredCount + other.coveredCount, groupId);
        }
        String key() { return branchGroupKey() + ":" + branchIndex; }
        String branchGroupKey() { return line + ":" + (groupId == null ? "" : groupId); }
        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }
        public int getBranchIndex() { return branchIndex; }
        public void setBranchIndex(int branchIndex) { this.branchIndex = branchIndex; }
        public int getCoveredCount() { return coveredCount; }
        public void setCoveredCount(int coveredCount) { this.coveredCount = coveredCount; }
        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
    }
}
