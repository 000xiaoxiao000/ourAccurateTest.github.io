package com.oAT.web.control.api;

import com.oAT.web.api.map.MapHomePayloadService;
import com.oAT.web.api.map.MapAppPayloadService;
import com.oAT.web.domain.ImageElement;
import com.oAT.web.esDao.StaticInfoRepository;
import com.oAT.web.esDao.entity.StaticSourceMethodInfo;
import com.oAT.web.verification.VerificationRepository;
import com.oAT.web.verification.model.VerificationModels.AssetSnapshot;
import com.oAT.web.verification.storage.AssetContentStore;
import com.oAT.web.exceptions.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/projects/{projectId}/map")
public class MapApiControl {

    private final MapHomePayloadService mapHomePayloadService;
    private final MapAppPayloadService mapAppPayloadService;
    private final StaticInfoRepository staticInfoRepository;
    private final VerificationRepository verificationRepository;
    private final AssetContentStore assetContentStore;

    public MapApiControl(MapHomePayloadService mapHomePayloadService,
                         MapAppPayloadService mapAppPayloadService,
                         StaticInfoRepository staticInfoRepository,
                         VerificationRepository verificationRepository,
                         AssetContentStore assetContentStore) {
        this.mapHomePayloadService = mapHomePayloadService;
        this.mapAppPayloadService = mapAppPayloadService;
        this.staticInfoRepository = staticInfoRepository;
        this.verificationRepository = verificationRepository;
        this.assetContentStore = assetContentStore;
    }

    @GetMapping("/home")
    public List<ImageElement> home(@PathVariable String projectId) {
        return mapHomePayloadService.buildHomeMapData(projectId);
    }

    @GetMapping("/apps/{appId}")
    public List<ImageElement> app(@PathVariable String projectId,
                                  @PathVariable String appId,
                                  @RequestParam(required = false) String layers) throws BusinessException {
        return mapAppPayloadService.buildAppMapData(projectId, appId, layers);
    }

    @GetMapping("/source-tree")
    public List<SourceTreeClass> sourceTree(@PathVariable String projectId,
                                            @RequestParam(required = false) String appId,
                                            @RequestParam(required = false) String sourceAssetId) {
        List<SourceTreeClass> indexed = appId == null || appId.isBlank()
                ? List.of()
                : staticInfoRepository.findByAppId(appId).stream()
                .filter(info -> info.getClassInfo() != null)
                .map(info -> new SourceTreeClass(
                        info.getId(),
                        info.getClassInfo().getClassName(),
                        info.getClassInfo().getMethodMaps() == null
                                ? List.of()
                                : info.getClassInfo().getMethodMaps().entrySet().stream()
                                .map(entry -> sourceTreeMethod(entry.getKey(), entry.getValue()))
                                .toList()))
                .toList();
        if (!indexed.isEmpty() || sourceAssetId == null || sourceAssetId.isBlank()) return indexed;
        return verificationRepository.findAsset(projectId, sourceAssetId)
                .map(this::sourceTreeFromAsset)
                .orElse(List.of());
    }

    private List<SourceTreeClass> sourceTreeFromAsset(AssetSnapshot asset) {
        String content = asset.storageKey() == null ? asset.content() : assetContentStore.load(asset.storageKey());
        if (content == null || content.isBlank()) content = asset.contentPreview();
        if (content == null || content.isBlank()) return List.of();
        List<SourceTreeClass> result = new ArrayList<>();
        String currentFile = asset.fileName() == null ? "ImportedSource.java" : asset.fileName();
        StringBuilder source = new StringBuilder();
        for (String line : content.split("\\R")) {
            if (line.trim().startsWith("// FILE:")) {
                appendParsedSource(result, currentFile, source.toString());
                currentFile = line.trim().substring("// FILE:".length()).trim();
                source.setLength(0);
            } else {
                source.append(line).append('\n');
            }
        }
        appendParsedSource(result, currentFile, source.toString());
        return result;
    }

    private void appendParsedSource(List<SourceTreeClass> result, String fileName, String source) {
        if (source.isBlank()) return;
        Pattern classPattern = Pattern.compile("(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
        Matcher classMatcher = classPattern.matcher(source);
        String className = classMatcher.find() ? classMatcher.group(1) : simpleFileName(fileName);
        Pattern methodPattern = Pattern.compile("(?m)^\\s*(?:public|protected|private|static|final|synchronized|abstract|native|default|\\s)+[\\w<>,.?\\[\\] ]+\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)\\s*(?:throws [^{]+)?\\{");
        Matcher methodMatcher = methodPattern.matcher(source);
        List<SourceTreeMethod> methods = new ArrayList<>();
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(1);
            int lineNumber = 1;
            for (int index = 0; index < methodMatcher.start(); index++) if (source.charAt(index) == '\n') lineNumber++;
            methods.add(new SourceTreeMethod(methodName, className, lineNumber));
        }
        result.add(new SourceTreeClass(UUID.nameUUIDFromBytes((fileName + className).getBytes()).toString(), fileName + " · " + className, methods));
    }

    private String simpleFileName(String fileName) {
        String normalized = fileName == null ? "ImportedSource" : fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private SourceTreeMethod sourceTreeMethod(String key, StaticSourceMethodInfo method) {
        List<Integer> lines = method == null ? null : method.getMethodLineNumberMap();
        return new SourceTreeMethod(
                method != null && method.getMethodName() != null ? method.getMethodName() : key,
                method == null ? null : method.getMethodDesc(),
                lines == null || lines.isEmpty() ? null : lines.get(0));
    }

    public record SourceTreeClass(String id, String className, List<SourceTreeMethod> methods) {}
    public record SourceTreeMethod(String methodName, String methodDesc, Integer lineNumber) {}

    @GetMapping("/code")
    public List<ImageElement> code(@PathVariable String projectId,
                                   @RequestParam String traceId) {
        return mapAppPayloadService.buildTraceStackCodeData(projectId, traceId);
    }
}
