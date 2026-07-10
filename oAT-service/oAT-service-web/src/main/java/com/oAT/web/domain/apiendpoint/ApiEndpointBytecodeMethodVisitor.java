package com.oAT.web.domain.apiendpoint;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

class ApiEndpointBytecodeMethodVisitor extends MethodVisitor {
    private static final Set<String> FEIGN_HTTP_ANNOTATIONS = new LinkedHashSet<>(Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "GET", "POST", "PUT", "DELETE", "PATCH", "HeadMapping", "RequestLine", "Headers",
            "HttpExchange", "GetExchange", "PostExchange", "PutExchange", "DeleteExchange", "PatchExchange"
    ));

    private final String className;
    private final String classBasePath;
    private final boolean feignClient;
    private final String feignBaseUrl;
    private final String rpcServiceName;
    private final String methodName;
    private final String methodDesc;
    private final String entryName;
    private final String sourceType;
    private final Map<String, ApiEndpointArtifactScanner.EndpointRecord> endpointMap;
    private final Set<String> stringConstants = new LinkedHashSet<>();
    private final Map<Integer, String> localStringValues = new HashMap<>();
    private final List<String> invocationTrail = new ArrayList<>();
    private final List<String> webClientPathSegments = new ArrayList<>();
    private final List<String> webClientQueryKeys = new ArrayList<>();
    private final Set<String> webClientHttpMethods = new LinkedHashSet<>();
    private final Set<String> rpcAnnotationTargets = new LinkedHashSet<>();
    private String mappingPath = "";
    private String mappingMethod = null;

ApiEndpointBytecodeMethodVisitor(MethodVisitor mv, String className, String classBasePath, boolean feignClient,
                          String feignBaseUrl, String rpcServiceName, String methodName, String methodDesc, String entryName,
                          String sourceType, Map<String, ApiEndpointArtifactScanner.EndpointRecord> endpointMap) {
        super(Opcodes.ASM5, mv);
        this.className = className;
        this.classBasePath = classBasePath;
        this.feignClient = feignClient;
        this.feignBaseUrl = feignBaseUrl;
        this.rpcServiceName = rpcServiceName;
        this.methodName = methodName;
        this.methodDesc = methodDesc;
        this.entryName = entryName;
        this.sourceType = sourceType;
        this.endpointMap = endpointMap;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        String ann = Type.getType(descriptor).getClassName();
        String simple = ann.substring(ann.lastIndexOf('.') + 1);
        if (!FEIGN_HTTP_ANNOTATIONS.contains(simple)) {
            if (isRpcMethodAnnotation(simple)) {
                return new MappingAnnotationVisitor(v -> {
                    if (StringUtils.hasText(v)) {
                        rpcAnnotationTargets.add(v);
                        if (!StringUtils.hasText(mappingPath)) {
                            mappingPath = v;
                        }
                    }
                });
            }
            return super.visitAnnotation(descriptor, visible);
        }
        return new AnnotationVisitor(Opcodes.ASM5) {
            @Override
            public void visit(String name, Object value) {
                if (("value".equals(name) || "path".equals(name) || "url".equals(name) || name == null) && value != null) {
                    if ("RequestLine".equals(simple)) {
                        applyRequestLineText(String.valueOf(value));
                    } else {
                        mappingPath = String.valueOf(value);
                    }
                }
                if (("method".equals(name) || name == null) && value != null) {
                    String enumValue = String.valueOf(value);
                    if (enumValue.contains(".")) {
                        enumValue = enumValue.substring(enumValue.lastIndexOf('.') + 1);
                    }
                    mappingMethod = enumValue;
                }
                if ("serviceInterface".equals(name) && value != null) {
                    rpcAnnotationTargets.add(String.valueOf(value));
                }
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                if ("method".equals(name)) {
                    mappingMethod = value;
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                if ("value".equals(name) || "path".equals(name)) {
                    return new AnnotationVisitor(Opcodes.ASM5) {
                        @Override
                        public void visit(String n, Object value) {
                            if (value != null && !StringUtils.hasText(mappingPath)) {
                                mappingPath = String.valueOf(value);
                            }
                        }
                    };
                }
                if ("method".equals(name)) {
                    return new AnnotationVisitor(Opcodes.ASM5) {
                        @Override
                        public void visitEnum(String n, String descriptor, String value) {
                            if (value != null && !StringUtils.hasText(mappingMethod)) {
                                mappingMethod = value;
                            }
                        }

                        @Override
                        public void visit(String n, Object value) {
                            if (value != null && !StringUtils.hasText(mappingMethod)) {
                                mappingMethod = String.valueOf(value);
                            }
                        }
                    };
                }
                return super.visitArray(name);
            }

            @Override
            public void visitEnd() {
                if (StringUtils.hasText(mappingPath) || StringUtils.hasText(mappingMethod) || "Headers".equals(simple)) {
                    ApiEndpointArtifactScanner.EndpointRecord record = new ApiEndpointArtifactScanner.EndpointRecord();
                    record.endpointType = feignClient ? "FEIGN" : "HTTP";
                    record.httpMethod = StringUtils.hasText(mappingMethod) ? mappingMethod : inferHttpMethod(simple);
                    record.url = normalizeStaticUrl(feignClient ? feignBaseUrl : classBasePath, mappingPath);
                    record.className = className;
                    record.methodName = methodName;
                    record.methodDesc = methodDesc;
                    record.sourceType = sourceType;
                    record.sourceName = entryName;
                    endpointMap.put(record.uniqueKey(), record);
                }
            }
        };
    }

    @Override
    public void visitLdcInsn(Object value) {
        if (value instanceof String) {
            stringConstants.add((String) value);
        }
        super.visitLdcInsn(value);
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
        if (opcode == Opcodes.ASTORE) {
            String latestString = latestStringConstant();
            if (StringUtils.hasText(latestString)) {
                localStringValues.put(varIndex, latestString);
            }
        }
        super.visitVarInsn(opcode, varIndex);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
        if ("makeConcatWithConstants".equals(name) && bootstrapMethodArguments != null) {
            for (Object arg : bootstrapMethodArguments) {
                if (arg instanceof String) {
                    String joined = ((String) arg).replace("\u0001", "");
                    if (StringUtils.hasText(joined)) {
                        stringConstants.add(joined);
                    }
                }
            }
        }
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        String ownerLower = owner.toLowerCase(Locale.ROOT);
        invocationTrail.add(owner + "#" + name);
        captureWebClientHttpMethod(ownerLower, name);
        captureWebClientUri(ownerLower, name);
        captureWebClientBuilderStep(ownerLower, name);
        if (owner.contains("RestTemplate") || owner.contains("HttpClient") || owner.contains("OkHttpClient")
                || owner.contains("WebTarget") || owner.contains("Request$Builder") || owner.contains("WebClient")
                || owner.contains("HttpRequest") || owner.contains("AsyncHttpClient")) {
            maybeAddHttpClientEndpoint(name, owner);
        }
        if (ownerLower.contains("dubbo") || ownerLower.contains("rpc") || ownerLower.contains("referenceconfig")
                || ownerLower.contains("servicebean") || ownerLower.contains("referencebean")) {
            maybeAddRpcEndpoint();
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    private void maybeAddHttpClientEndpoint(String invokeName, String owner) {
        List<String> candidates = stringConstants.stream()
                .filter(this::looksLikeHttpTarget)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return;
        }
        ApiEndpointArtifactScanner.EndpointRecord record = new ApiEndpointArtifactScanner.EndpointRecord();
        record.endpointType = "HTTP_CLIENT";
        record.httpMethod = guessClientHttpMethod(invokeName, owner, candidates);
        String candidateUrl = resolveBestHttpTarget(candidates);
        if (!StringUtils.hasText(candidateUrl)) {
            return;
        }
        record.url = ApiEndpointArtifactScanner.simplifyEndpointTarget(normalizeUriTemplate(candidateUrl));
        record.className = className;
        record.methodName = methodName;
        record.methodDesc = methodDesc;
        record.sourceType = sourceType;
        record.sourceName = entryName;
        endpointMap.put(record.uniqueKey(), record);
    }

    private void maybeAddRpcEndpoint() {
        Set<String> candidates = new LinkedHashSet<>(rpcAnnotationTargets);
        stringConstants.stream()
                .filter(this::looksLikeRpcTarget)
                .forEach(candidates::add);
        String rpcTarget = resolveRpcTarget(candidates);
        if (!StringUtils.hasText(rpcTarget)) {
            return;
        }
        ApiEndpointArtifactScanner.EndpointRecord record = new ApiEndpointArtifactScanner.EndpointRecord();
        record.endpointType = "RPC";
        record.httpMethod = "INVOKE";
        record.url = buildRpcTarget(rpcTarget, methodName, new ArrayList<>(candidates));
        record.className = className;
        record.methodName = methodName;
        record.methodDesc = methodDesc;
        record.sourceType = sourceType;
        record.sourceName = entryName;
        endpointMap.put(record.uniqueKey(), record);
    }

    private void captureWebClientHttpMethod(String ownerLower, String invokeName) {
        if (!ownerLower.contains("webclient")) {
            return;
        }
        String method = invokeName == null ? "" : invokeName.toLowerCase(Locale.ROOT);
        if (Arrays.asList("get", "post", "put", "delete", "patch", "head", "options").contains(method)) {
            webClientHttpMethods.add(method.toUpperCase(Locale.ROOT));
        }
    }

    private void captureWebClientUri(String ownerLower, String invokeName) {
        if (!ownerLower.contains("webclient") || !"uri".equalsIgnoreCase(invokeName)) {
            return;
        }
        String latest = latestStringConstant();
        if (StringUtils.hasText(latest)) {
            stringConstants.add(latest);
        }
        localStringValues.values().stream().filter(StringUtils::hasText).forEach(stringConstants::add);
        String builderPath = buildWebClientBuilderPath();
        if (StringUtils.hasText(builderPath)) {
            stringConstants.add(builderPath);
        }
    }

    private void captureWebClientBuilderStep(String ownerLower, String invokeName) {
        if (!ownerLower.contains("uri") && !ownerLower.contains("webclient")) {
            return;
        }
        String method = invokeName == null ? "" : invokeName.toLowerCase(Locale.ROOT);
        String latest = latestStringConstant();
        if ("path".equals(method) && StringUtils.hasText(latest)) {
            webClientPathSegments.add(latest);
        }
        if ("queryparam".equals(method) && StringUtils.hasText(latest)) {
            webClientQueryKeys.add(latest);
        }
        if (("build".equals(method) || "touri".equals(method)) && StringUtils.hasText(buildWebClientBuilderPath())) {
            stringConstants.add(buildWebClientBuilderPath());
        }
    }

    private String buildWebClientBuilderPath() {
        String path = webClientPathSegments.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeUriTemplate)
                .collect(Collectors.joining(""));
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (webClientQueryKeys.isEmpty()) {
            return normalizedPath;
        }
        String query = webClientQueryKeys.stream()
                .filter(StringUtils::hasText)
                .map(key -> key + "={}")
                .collect(Collectors.joining("&"));
        return normalizedPath + "?" + query;
    }

    private String resolveBestHttpTarget(List<String> candidates) {
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String candidate = candidates.get(i);
            if (looksLikeHttpTarget(candidate) && !isLikelyHttpMethodLiteral(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private String resolveRpcTarget(Set<String> candidates) {
        if (StringUtils.hasText(rpcServiceName)) {
            return rpcServiceName;
        }
        List<String> ordered = candidates.stream().filter(StringUtils::hasText).collect(Collectors.toList());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            String candidate = ordered.get(i);
            if (candidate.endsWith(".class") || candidate.contains("#") || candidate.contains("com.")) {
                return candidate;
            }
        }
        return ordered.isEmpty() ? "" : ordered.get(ordered.size() - 1);
    }

    private boolean isLikelyHttpMethodLiteral(String value) {
        String upper = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(upper);
    }

    private String latestStringConstant() {
        return stringConstants.stream().filter(StringUtils::hasText).reduce((first, second) -> second).orElse("");
    }

    private String normalizeUriTemplate(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.replaceAll("\\{[^/]+}", "{}");
        normalized = normalized.replaceAll("\\$\\{[^/]+}", "{}");
        return normalized;
    }

    private void applyRequestLineText(String requestLine) {
        if (!StringUtils.hasText(requestLine)) {
            return;
        }
        String[] parts = requestLine.trim().split("\\s+", 2);
        if (parts.length == 2) {
            mappingMethod = parts[0].toUpperCase(Locale.ROOT);
            mappingPath = parts[1].trim();
        } else {
            mappingPath = requestLine.trim();
        }
    }

    private String buildRpcTarget(String service, String methodName, List<String> candidates) {
        String targetService = service;
        if (targetService.endsWith(".class")) {
            targetService = targetService.substring(0, targetService.length() - 6);
        }
        if (targetService.contains("#")) {
            return targetService;
        }
        String candidateMethod = candidates.stream()
                .filter(v -> v.contains("#"))
                .reduce((first, second) -> second)
                .orElse(null);
        if (StringUtils.hasText(candidateMethod)) {
            return candidateMethod;
        }
        String resolvedMethod = extractRpcMethodCandidate(candidates);
        if (!StringUtils.hasText(resolvedMethod)) {
            resolvedMethod = StringUtils.hasText(methodName) ? methodName : this.methodName;
        }
        return targetService + "#" + resolvedMethod;
    }

    private String extractRpcMethodCandidate(List<String> candidates) {
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String candidate = candidates.get(i);
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            String cleaned = candidate.trim();
            if (cleaned.contains("(") && cleaned.endsWith(")")) {
                int idx = cleaned.indexOf('(');
                return cleaned.substring(0, idx);
            }
            if (cleaned.matches("[a-zA-Z_$][\\w$]*")) {
                return cleaned;
            }
        }
        return "";
    }

    private String inferHttpMethod(String simple) {
        switch (simple) {
            case "GetMapping":
            case "GET":
            case "GetExchange": return "GET";
            case "PostMapping":
            case "POST":
            case "PostExchange": return "POST";
            case "PutMapping":
            case "PUT":
            case "PutExchange": return "PUT";
            case "DeleteMapping":
            case "DELETE":
            case "DeleteExchange": return "DELETE";
            case "PatchMapping":
            case "PATCH":
            case "PatchExchange": return "PATCH";
            case "HEAD":
            case "HeadMapping": return "HEAD";
            default: return "ALL";
        }
    }

    private boolean looksLikeHttpTarget(String value) {
        return StringUtils.hasText(value) && (value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("/") || value.startsWith("${") || value.startsWith("#{"));
    }

    private boolean looksLikeRpcTarget(String value) {
        return StringUtils.hasText(value) && (value.contains("#") || value.endsWith(".class")
                || value.contains(".") || value.contains("/"));
    }

    private String guessClientHttpMethod(String invokeName, String owner, List<String> candidates) {
        String name = invokeName == null ? "" : invokeName.toLowerCase(Locale.ROOT);
        String ownerName = owner == null ? "" : owner.toLowerCase(Locale.ROOT);
        if (name.contains("post")) return "POST";
        if (name.contains("put")) return "PUT";
        if (name.contains("delete")) return "DELETE";
        if (name.contains("patch")) return "PATCH";
        if (name.contains("head")) return "HEAD";
        if (name.contains("option")) return "OPTIONS";
        if (ownerName.contains("webclient") && !webClientHttpMethods.isEmpty()) {
            return webClientHttpMethods.stream().filter(Objects::nonNull).findFirst().orElse("GET");
        }
        if (name.contains("method") || name.contains("exchange")) {
            String upperCandidate = candidates.stream()
                    .map(String::trim)
                    .filter(this::isLikelyHttpMethodLiteral)
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (StringUtils.hasText(upperCandidate)) {
                return upperCandidate.toUpperCase(Locale.ROOT);
            }
        }
        if (ownerName.contains("webclient") && (name.contains("retrieve") || name.contains("exchange"))) {
            return !webClientHttpMethods.isEmpty() ? webClientHttpMethods.iterator().next() : "GET";
        }
        return "GET";
    }

    private String normalizeStaticUrl(String base, String path) {
        return normalizeUrl(base, path);
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

    private static boolean isRpcMethodAnnotation(String simple) {
        return simple.startsWith("DubboReference") || simple.startsWith("Reference") || simple.contains("RpcReference")
                || simple.contains("Consumer") || simple.contains("ReferenceBean");
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

}
