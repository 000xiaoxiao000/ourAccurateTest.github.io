package com.oAT.web.domain.apiendpoint;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ApiEndpointArtifactScanner {
    private static final Logger logger = LoggerFactory.getLogger(ApiEndpointArtifactScanner.class);
    private static final Set<String> HTTP_MAPPING_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "HeadMapping"
    ));
    private static final Set<String> FEIGN_HTTP_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "GET", "POST", "PUT", "DELETE", "PATCH", "HeadMapping", "RequestLine", "Headers",
            "HttpExchange", "GetExchange", "PostExchange", "PutExchange", "DeleteExchange", "PatchExchange"
    ));
    private static final Set<String> FEIGN_CLIENT_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "FeignClient", "Client", "HttpExchange"
    ));


    public Collection<EndpointRecord> scanArtifact(File file, String sourceName) throws IOException {
        String originalName = sourceName == null ? file.getName() : sourceName;
        String lowerName = originalName.toLowerCase(Locale.ROOT);
        Map<String, EndpointRecord> endpointMap = new LinkedHashMap<>();
        if (lowerName.endsWith(".zip")) {
            scanZip(file, endpointMap);
        } else if (lowerName.endsWith(".jar") || lowerName.endsWith(".war")) {
            scanArchiveFile(file, lowerName.endsWith(".war"), endpointMap);
        } else {
            throw new IllegalArgumentException("仅支持 zip、jar、war 文件");
        }
        return endpointMap.values();
    }

    private void scanZip(File zipFile, Map<String, EndpointRecord> endpointMap) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] bytes = zis.readAllBytes();
                if (name.endsWith(".java")) {
                    scanJavaSource(name, new String(bytes, StandardCharsets.UTF_8), endpointMap, "zip");
                } else if (name.endsWith(".class")) {
                    scanClassBytes(name, bytes, endpointMap, "zip");
                } else if (name.endsWith(".jar") || name.endsWith(".war")) {
                    scanNestedArchive(name, bytes, endpointMap);
                }
            }
        }
    }

    private void scanArchiveFile(File archiveFile, boolean war, Map<String, EndpointRecord> endpointMap) throws IOException {
        try (JarFile jarFile = new JarFile(archiveFile)) {
            jarFile.stream().filter(e -> !e.isDirectory()).forEach(entry -> {
                try (InputStream in = jarFile.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    String name = entry.getName();
                    if (name.endsWith(".class")) {
                        scanClassBytes(name, bytes, endpointMap, war ? "war" : "jar");
                    } else if (name.endsWith(".jar")) {
                        scanNestedArchive(name, bytes, endpointMap);
                    }
                } catch (Exception ex) {
                    logger.warn("scan archive entry failed: {}", entry.getName(), ex);
                }
            });
        }
    }

    private void scanNestedArchive(String name, byte[] bytes, Map<String, EndpointRecord> endpointMap) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry nested;
            while ((nested = zis.getNextEntry()) != null) {
                if (nested.isDirectory()) {
                    continue;
                }
                String nestedName = name + "!/" + nested.getName();
                byte[] nestedBytes = zis.readAllBytes();
                if (nestedName.endsWith(".class")) {
                    scanClassBytes(nestedName, nestedBytes, endpointMap, "nested");
                } else if (nestedName.endsWith(".jar")) {
                    scanNestedArchive(nestedName, nestedBytes, endpointMap);
                }
            }
        }
    }

    private void scanJavaSource(String entryName, String source, Map<String, EndpointRecord> endpointMap, String sourceType) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(source);
            for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String className = type.getFullyQualifiedName().orElseGet(type::getNameAsString);
                boolean feignClient = hasFeignClientAnnotation(type.getAnnotations());
                String classBasePath = extractRequestPath(type.getAnnotations());
                String feignBasePath = extractFeignClientPath(type.getAnnotations());
                String rpcServiceName = extractRpcService(type.getAnnotations(), className);
                for (MethodDeclaration method : type.getMethods()) {
                    MappingMeta mapping = extractHttpMapping(method.getAnnotations());
                    if (mapping != null) {
                        EndpointRecord record = new EndpointRecord();
                        record.endpointType = feignClient ? "FEIGN" : "HTTP";
                        record.httpMethod = StringUtils.hasText(mapping.httpMethod) ? mapping.httpMethod : "ALL";
                        record.url = normalizeUrl(feignClient ? feignBasePath : classBasePath, mapping.path);
                        record.className = className;
                        record.methodName = method.getNameAsString();
                        record.methodDesc = method.getSignature().asString();
                        record.sourceType = sourceType;
                        record.sourceName = entryName;
                        endpointMap.put(record.uniqueKey(), record);
                    }
                    if (hasRpcReferenceAnnotation(method.getAnnotations())) {
                        EndpointRecord rpcRecord = buildRpcRecord(className, method.getNameAsString(), method.getSignature().asString(),
                                sourceType, entryName, extractRpcService(method.getAnnotations(), rpcServiceName));
                        endpointMap.put(rpcRecord.uniqueKey(), rpcRecord);
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("parse java source failed: {}", entryName, ex);
        }
    }

    private void scanClassBytes(String entryName, byte[] bytes, Map<String, EndpointRecord> endpointMap, String sourceType) {
        try {
            ClassReader reader = new ClassReader(bytes);
            reader.accept(new EndpointClassVisitor(entryName, sourceType, endpointMap), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception ex) {
            logger.debug("parse class failed: {}", entryName, ex);
        }
    }

    private String extractRequestPath(List<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            if ("RequestMapping".equals(annotation.getNameAsString())) {
                String path = readAnnotationPath(annotation);
                if (StringUtils.hasText(path)) {
                    return path;
                }
            }
        }
        return "";
    }

    private String extractFeignClientPath(List<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            if (FEIGN_CLIENT_ANNOTATIONS.contains(annotation.getNameAsString())) {
                String path = readAnnotationPath(annotation);
                if (StringUtils.hasText(path)) {
                    return path;
                }
            }
        }
        return "";
    }

    private boolean hasFeignClientAnnotation(List<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(annotation -> FEIGN_CLIENT_ANNOTATIONS.contains(annotation.getNameAsString()));
    }

    private String extractRpcService(List<AnnotationExpr> annotations, String defaultService) {
        for (AnnotationExpr annotation : annotations) {
            if (isRpcClassAnnotation(annotation.getNameAsString()) || isRpcMethodAnnotation(annotation.getNameAsString())) {
                String path = readAnnotationPath(annotation);
                if (StringUtils.hasText(path)) {
                    return path;
                }
            }
        }
        return defaultService;
    }

    private boolean hasRpcReferenceAnnotation(List<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(annotation -> isRpcMethodAnnotation(annotation.getNameAsString()));
    }

    private MappingMeta extractHttpMapping(List<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            String name = annotation.getNameAsString();
            if (!HTTP_MAPPING_ANNOTATIONS.contains(name) && !"RequestLine".equals(name)) {
                continue;
            }
            MappingMeta meta = new MappingMeta();
            if ("RequestLine".equals(name)) {
                applyRequestLineMeta(meta, readRequestLine(annotation));
            } else {
                meta.path = readAnnotationPath(annotation);
                meta.httpMethod = deduceHttpMethod(name, annotation);
            }
            return meta;
        }
        return null;
    }

    private String deduceHttpMethod(String annotationName, AnnotationExpr annotationExpr) {
        switch (annotationName) {
            case "GetMapping":
            case "GET": return "GET";
            case "PostMapping":
            case "POST": return "POST";
            case "PutMapping":
            case "PUT": return "PUT";
            case "DeleteMapping":
            case "DELETE": return "DELETE";
            case "PatchMapping":
            case "PATCH": return "PATCH";
            case "HEAD":
            case "HeadMapping": return "HEAD";
            default:
                if (annotationExpr instanceof NormalAnnotationExpr) {
                    for (MemberValuePair pair : ((NormalAnnotationExpr) annotationExpr).getPairs()) {
                        if ("method".equals(pair.getNameAsString())) {
                            String value = pair.getValue().toString();
                            int dot = value.lastIndexOf('.');
                            return dot >= 0 ? value.substring(dot + 1) : value;
                        }
                    }
                }
                return "ALL";
        }
    }

    private String readAnnotationPath(AnnotationExpr annotationExpr) {
        if (annotationExpr instanceof SingleMemberAnnotationExpr) {
            return trimQuotes(((SingleMemberAnnotationExpr) annotationExpr).getMemberValue().toString());
        }
        if (annotationExpr instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) annotationExpr).getPairs()) {
                if ("value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString()) || "url".equals(pair.getNameAsString())) {
                    return extractExpressionValue(pair.getValue());
                }
            }
        }
        return "";
    }

    private String readRequestLine(AnnotationExpr annotationExpr) {
        String value = readAnnotationPath(annotationExpr);
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private void applyRequestLineMeta(MappingMeta meta, String requestLine) {
        if (!StringUtils.hasText(requestLine)) {
            meta.httpMethod = "ALL";
            meta.path = "";
            return;
        }
        String[] parts = requestLine.trim().split("\\s+", 2);
        if (parts.length == 2) {
            meta.httpMethod = parts[0].toUpperCase(Locale.ROOT);
            meta.path = parts[1].trim();
        } else {
            meta.httpMethod = "ALL";
            meta.path = requestLine.trim();
        }
    }

    private String extractExpressionValue(Expression expression) {
        if (expression instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expression).getValue();
        }
        String raw = expression.toString();
        if (raw.startsWith("{") && raw.endsWith("}")) {
            raw = raw.substring(1, raw.length() - 1).split(",")[0].trim();
        }
        if (raw.endsWith(".class")) {
            return raw.substring(0, raw.length() - 6);
        }
        return trimQuotes(raw);
    }

    private String trimQuotes(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() >= 2) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }

    private String joinValues(Set<String> values) {
        return values.stream().filter(StringUtils::hasText).collect(Collectors.joining("\n"));
    }

    private String defaultIfBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private List<String> splitLines(String preferred, String fallback) {
        String value = defaultIfBlank(preferred, fallback);
        if (!StringUtils.hasText(value)) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split("\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    private String normalizeUrl(String base, String path) {
        String left = normalizeEndpointSegment(base);
        String right = normalizeEndpointSegment(path);
        if (!StringUtils.hasText(left) && !StringUtils.hasText(right)) {
            return "/";
        }
        if (left.startsWith("http://") || left.startsWith("https://")) {
            if (StringUtils.hasText(right)) {
                if (right.startsWith("http://") || right.startsWith("https://")) {
                    return right;
                }
                return (left.replaceAll("/+$", "") + "/" + right.replaceAll("^/+", "")).replaceAll("(?<!:)/{2,}", "/");
            }
            return left;
        }
        String merged = (left + right).replaceAll("//+", "/");
        return StringUtils.hasText(merged) ? merged : "/";
    }

    private String normalizeEndpointSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String result = value.trim();
        if ("/".equals(result)) {
            return result;
        }
        if (result.endsWith(".class")) {
            result = result.substring(0, result.length() - 6);
        }
        if (result.startsWith("http://") || result.startsWith("https://")) {
            return result.replaceAll("(?<!:)/{2,}", "/").replaceFirst("^http:/", "http://").replaceFirst("^https:/", "https://");
        }
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        return result;
    }

    private EndpointRecord buildRpcRecord(String className, String methodName, String methodDesc,
                                          String sourceType, String sourceName, String rpcServiceName) {
        EndpointRecord record = new EndpointRecord();
        record.endpointType = "RPC";
        record.httpMethod = "INVOKE";
        String targetService = StringUtils.hasText(rpcServiceName) ? rpcServiceName : className;
        if (targetService.endsWith(".class")) {
            targetService = targetService.substring(0, targetService.length() - 6);
        }
        record.url = targetService.contains("#") ? targetService : targetService + "#" + methodName;
        record.className = className;
        record.methodName = methodName;
        record.methodDesc = methodDesc;
        record.sourceType = sourceType;
        record.sourceName = sourceName;
        return record;
    }

    private static class MappingMeta {
        private String path;
        String httpMethod;
    }

    static String normalizeEndpointMethod(String httpMethod) {
        if (!StringUtils.hasText(httpMethod)) {
            return "ALL";
        }
        return httpMethod.trim().toUpperCase(Locale.ROOT);
    }

    static String simplifyEndpointTarget(String target) {
        if (!StringUtils.hasText(target)) {
            return "";
        }
        return target.trim().replaceAll("//+", "/");
    }

    static class EndpointRecord {
        String endpointType;
        String url;
        String httpMethod;
        String className;
        String methodName;
        String methodDesc;
        String sourceType;
        String sourceName;

        String uniqueKey() {
            return endpointType + "|" + httpMethod + "|" + url + "|" + className + "|" + methodName + "|" + methodDesc;
        }

        String mergeKey() {
            return endpointType + "|" + normalizeEndpointMethod(httpMethod) + "|" + simplifyEndpointTarget(url);
        }

        String endpointKey() {
            String target = url == null ? "" : url;
            if ("RPC".equals(endpointType)) {
                return endpointType + "|" + normalizeEndpointMethod(httpMethod) + "|" + target.trim();
            }
            return endpointType + "|" + normalizeEndpointMethod(httpMethod) + "|" + simplifyEndpointTarget(target);
        }
    }

    private class EndpointClassVisitor extends ClassVisitor {
        private final String entryName;
        private final String sourceType;
        private final Map<String, EndpointRecord> endpointMap;
        String className;
        private String classBasePath = "";
        private boolean feignClient;
        private String feignBaseUrl = "";
        private String rpcServiceName = "";

        EndpointClassVisitor(String entryName, String sourceType, Map<String, EndpointRecord> endpointMap) {
            super(Opcodes.ASM5);
            this.entryName = entryName;
            this.sourceType = sourceType;
            this.endpointMap = endpointMap;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name.replace('/', '.');
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String ann = Type.getType(descriptor).getClassName();
            String simple = ann.substring(ann.lastIndexOf('.') + 1);
            if ("RequestMapping".equals(simple)) {
                return new MappingAnnotationVisitor(v -> classBasePath = v);
            }
            if (FEIGN_CLIENT_ANNOTATIONS.contains(simple)) {
                feignClient = true;
                return new MappingAnnotationVisitor(v -> feignBaseUrl = v);
            }
            if (isRpcClassAnnotation(simple)) {
                return new MappingAnnotationVisitor(v -> rpcServiceName = v);
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new ApiEndpointBytecodeMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions), className,
                    classBasePath, feignClient, feignBaseUrl, rpcServiceName, name, descriptor, entryName, sourceType, endpointMap);
        }
    }

    private static class MappingAnnotationVisitor extends AnnotationVisitor {
        private final java.util.function.Consumer<String> consumer;

        MappingAnnotationVisitor(java.util.function.Consumer<String> consumer) {
            super(Opcodes.ASM5);
            this.consumer = consumer;
        }

        @Override
        public void visit(String name, Object value) {
            if (("value".equals(name) || "path".equals(name) || "url".equals(name) || "name".equals(name)
                    || "serviceInterface".equals(name) || name == null) && value != null) {
                consumer.accept(String.valueOf(value));
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("value".equals(name) || "path".equals(name) || "url".equals(name) || name == null) {
                return new AnnotationVisitor(Opcodes.ASM5) {
                    @Override
                    public void visit(String n, Object value) {
                        if (value != null) {
                            consumer.accept(String.valueOf(value));
                        }
                    }
                };
            }
            return super.visitArray(name);
        }
    }

    private static boolean isRpcClassAnnotation(String simple) {
        return simple.startsWith("DubboService") || simple.startsWith("Service") || simple.startsWith("Provider")
                || simple.contains("RpcService") || simple.contains("RemoteService");
    }

    private static boolean isRpcMethodAnnotation(String simple) {
        return simple.startsWith("DubboReference") || simple.startsWith("Reference") || simple.contains("RpcReference")
                || simple.contains("Consumer") || simple.contains("ReferenceBean");
    }
}
