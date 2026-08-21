package com.oAT.web.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.oAT.web.service.UsecaseFileService;
import com.oAT.web.service.UsecaseService;
import com.oAT.web.service.entity.UsecaseDirectoryVo;
import com.oAT.web.service.entity.UsecaseImportError;
import com.oAT.web.service.entity.UsecaseImportResult;
import com.oAT.web.service.entity.UsecaseImportRow;
import com.oAT.web.service.entity.UsecaseVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class UsecaseFileServiceImpl implements UsecaseFileService {

    private static final String DEFAULT_DIRECTORY = "root";
    private static final Pattern EXPORT_ID_PATTERN = Pattern.compile("^(.+?)（ID：([^）]+)）$");

    @Autowired
    private UsecaseService usecaseService;

    @Override
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        setExcelResponse(response, "用例导入模板");
        List<UsecaseImportRow> rows = new ArrayList<>();
        UsecaseImportRow sample = new UsecaseImportRow();
        sample.setTitle("登录成功流程");
        sample.setDirectory("ROOT");
        sample.setContent("## 前置条件\n- 用户已注册\n\n## 操作步骤\n1. 打开登录页\n2. 输入账号密码\n3. 点击登录\n\n## 预期结果\n成功进入首页");
        sample.setLabels("冒烟,登录");
        sample.setDefects("BUG-1001");
        sample.setPrdRequirements("PRD-2026-001");
        rows.add(sample);
        EasyExcel.write(response.getOutputStream(), UsecaseImportRow.class)
                .sheet("用例导入模板")
                .doWrite(rows);
    }

    @Override
    public UsecaseImportResult importUsecases(String projectId, String operator, String currentDirectory, String appId, MultipartFile file) throws IOException {
        UsecaseImportResult result = new UsecaseImportResult();
        // A1 需求全系统级：导入必须指定归属系统
        if (!StringUtils.hasText(appId)) {
            result.getErrors().add(new UsecaseImportError(0, "appId", "请选择导入目标系统（需求必须归属系统）", null));
            result.setFailureCount(1);
            return result;
        }
        if (file == null || file.isEmpty()) {
            result.getErrors().add(new UsecaseImportError(0, "file", "请选择要上传的用例 Excel 文件", null));
            result.setFailureCount(1);
            return result;
        }
        String originalFilename = file.getOriginalFilename();
        if (!hasExcelExtension(originalFilename)) {
            result.getErrors().add(new UsecaseImportError(0, "file", "仅支持上传 .xlsx 或 .xls 文件", originalFilename));
            result.setFailureCount(1);
            return result;
        }

        List<UsecaseImportRow> rows = readRows(file);
        result.setTotalCount(rows.size());
        if (rows.isEmpty()) {
            result.getErrors().add(new UsecaseImportError(0, "file", "Excel 中没有可导入的用例数据", originalFilename));
            result.setFailureCount(1);
            return result;
        }

        String defaultDirectory = normalizeDefaultDirectory(currentDirectory);
        Map<String, String> directoryMap = buildDirectoryMap(projectId);
        List<UsecaseVo> toSave = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            UsecaseImportRow row = rows.get(i);
            int rowNumber = i + 2;
            validateRow(projectId, row, rowNumber, defaultDirectory, directoryMap, result, toSave, appId);
        }

        if (result.hasErrors()) {
            result.setFailureCount(result.getErrors().size());
            result.setSuccessCount(0);
            return result;
        }

        for (UsecaseVo usecase : toSave) {
            usecaseService.doAdd(operator, usecase);
        }
        result.setSuccessCount(toSave.size());
        result.setFailureCount(0);
        return result;
    }

    @Override
    public void exportUsecases(String projectId, String directory, String sort, String keyword, HttpServletResponse response) throws IOException {
        String normalizedDirectory = normalizeDefaultDirectory(directory);
        String normalizedSort = StringUtils.hasText(sort) ? sort : "updateTime";
        List<UsecaseVo> usecases = usecaseService.getUsecases(projectId, normalizedDirectory, normalizedSort, keyword, null);
        Map<String, String> directoryNameMap = buildDirectoryNameMap(projectId);
        List<UsecaseImportRow> rows = usecases.stream()
                .map(usecase -> toExportRow(usecase, directoryNameMap))
                .collect(Collectors.toList());
        setExcelResponse(response, "用例导出");
        EasyExcel.write(response.getOutputStream(), UsecaseImportRow.class)
                .sheet("用例导出")
                .doWrite(rows);
    }

    private void validateRow(String projectId, UsecaseImportRow row, int rowNumber, String defaultDirectory,
                             Map<String, String> directoryMap, UsecaseImportResult result, List<UsecaseVo> toSave, String appId) {
        if (row == null || isBlankRow(row)) {
            return;
        }
        String title = trim(row.getTitle());
        if (!StringUtils.hasText(title)) {
            result.getErrors().add(new UsecaseImportError(rowNumber, "用例名称", "用例名称不能为空", row == null ? null : row.getTitle()));
            return;
        }
        if (title.length() < 4) {
            result.getErrors().add(new UsecaseImportError(rowNumber, "用例名称", "用例名称至少包含4个字符", title));
            return;
        }
        if (title.length() > 50) {
            result.getErrors().add(new UsecaseImportError(rowNumber, "用例名称", "用例名称不能超过50个字符", title));
            return;
        }

        String directoryId = resolveDirectoryId(row.getDirectory(), defaultDirectory, directoryMap);
        if (!StringUtils.hasText(directoryId)) {
            result.getErrors().add(new UsecaseImportError(rowNumber, "所属目录", "所属目录不存在；可填写 ROOT、目录名称、目录路径或目录ID", row.getDirectory()));
            return;
        }

        UsecaseVo usecase = new UsecaseVo();
        usecase.setProjectId(projectId);
        usecase.setAppId(appId);
        usecase.setTitle(title);
        usecase.setDirectory(directoryId);
        usecase.setContent(trimToNull(row.getContent()));
        usecase.setHeadImage(trimToNull(row.getHeadImage()));
        usecase.setLabels(splitValues(row.getLabels()));
        usecase.setDefects(splitValues(row.getDefects()));
        usecase.setPrdRequirements(splitValues(row.getPrdRequirements()));
        toSave.add(usecase);
    }

    private List<UsecaseImportRow> readRows(MultipartFile file) throws IOException {
        List<UsecaseImportRow> rows = new ArrayList<>();
        EasyExcel.read(file.getInputStream(), UsecaseImportRow.class, new AnalysisEventListener<UsecaseImportRow>() {
            @Override
            public void invoke(UsecaseImportRow data, AnalysisContext context) {
                if (!isBlankRow(data)) {
                    rows.add(data);
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
            }
        }).sheet().doRead();
        return rows;
    }

    private UsecaseImportRow toExportRow(UsecaseVo usecase, Map<String, String> directoryNameMap) {
        UsecaseImportRow row = new UsecaseImportRow();
        row.setTitle(usecase.getTitle());
        row.setDirectory(directoryNameMap.getOrDefault(usecase.getDirectory(), "ROOT"));
        row.setContent(usecase.getContent());
        row.setLabels(joinValues(usecase.getLabels()));
        row.setDefects(joinValues(usecase.getDefects()));
        row.setPrdRequirements(joinValues(usecase.getPrdRequirements()));
        row.setHeadImage(usecase.getHeadImage());
        return row;
    }

    private Map<String, String> buildDirectoryMap(String projectId) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("root", DEFAULT_DIRECTORY);
        result.put("ROOT", DEFAULT_DIRECTORY);
        collectDirectoryMap(projectId, DEFAULT_DIRECTORY, "ROOT", result);
        return result;
    }

    private Map<String, String> buildDirectoryNameMap(String projectId) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(DEFAULT_DIRECTORY, "ROOT");
        collectDirectoryNameMap(projectId, DEFAULT_DIRECTORY, "ROOT", result);
        return result;
    }

    private void collectDirectoryMap(String projectId, String parentId, String parentPath, Map<String, String> result) {
        List<UsecaseDirectoryVo> directories = usecaseService.getDirectory(projectId, parentId);
        for (UsecaseDirectoryVo directory : directories) {
            String name = trim(directory.getName());
            String path = parentPath + "/" + name;
            result.put(directory.getId(), directory.getId());
            result.putIfAbsent(name, directory.getId());
            result.put(path, directory.getId());
            result.put(path.substring("ROOT/".length()), directory.getId());
            collectDirectoryMap(projectId, directory.getId(), path, result);
        }
    }

    private void collectDirectoryNameMap(String projectId, String parentId, String parentPath, Map<String, String> result) {
        List<UsecaseDirectoryVo> directories = usecaseService.getDirectory(projectId, parentId);
        for (UsecaseDirectoryVo directory : directories) {
            String path = parentPath + "/" + trim(directory.getName());
            result.put(directory.getId(), path);
            collectDirectoryNameMap(projectId, directory.getId(), path, result);
        }
    }

    private String resolveDirectoryId(String directory, String defaultDirectory, Map<String, String> directoryMap) {
        String value = trim(directory);
        if (!StringUtils.hasText(value)) {
            return defaultDirectory;
        }
        if ("/".equals(value) || "ROOT".equalsIgnoreCase(value)) {
            return DEFAULT_DIRECTORY;
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return directoryMap.get(normalized);
    }

    private String normalizeDefaultDirectory(String directory) {
        return StringUtils.hasText(directory) ? directory.trim() : DEFAULT_DIRECTORY;
    }

    private boolean hasExcelExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    private boolean isBlankRow(UsecaseImportRow row) {
        return row == null
                || !StringUtils.hasText(row.getTitle())
                && !StringUtils.hasText(row.getDirectory())
                && !StringUtils.hasText(row.getContent())
                && !StringUtils.hasText(row.getLabels())
                && !StringUtils.hasText(row.getDefects())
                && !StringUtils.hasText(row.getPrdRequirements())
                && !StringUtils.hasText(row.getHeadImage());
    }

    private String[] splitValues(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        LinkedHashSet<String> values = Arrays.stream(text.split("[\\r\\n,，;；]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return values.isEmpty() ? null : values.toArray(new String[0]);
    }

    private String[] splitRelationValues(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        LinkedHashSet<String> values = Arrays.stream(text.split("[\\r\\n,，;；]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(this::extractRelationId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return values.isEmpty() ? null : values.toArray(new String[0]);
    }

    private String extractRelationId(String value) {
        Matcher matcher = EXPORT_ID_PATTERN.matcher(value);
        return matcher.matches() ? matcher.group(2).trim() : value;
    }

    private String joinValues(String[] values) {
        if (values == null || values.length == 0) {
            return null;
        }
        return Arrays.stream(values)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(","));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trim(value);
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private void setExcelResponse(HttpServletResponse response, String filename) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String encodedFileName = URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + encodedFileName + ".xlsx");
    }
}
