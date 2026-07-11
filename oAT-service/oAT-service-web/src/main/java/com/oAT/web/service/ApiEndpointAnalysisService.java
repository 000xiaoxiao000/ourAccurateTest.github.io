package com.oAT.web.service;

import com.oAT.web.domain.apiendpoint.ApiEndpointArtifactScanner;
import com.oAT.web.domain.apiendpoint.ApiEndpointPersistenceService;
import com.oAT.web.esDao.ApiEndpointRepository;
import com.oAT.web.esDao.entity.ApiEndpointIndex;
import com.oAT.web.service.entity.ApiEndpointViewVo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

@Service
public class ApiEndpointAnalysisService {
    private final ApiEndpointArtifactScanner scanner;
    private final ApiEndpointPersistenceService persistenceService;
    private final ApiEndpointRepository repository;

    public ApiEndpointAnalysisService(ApiEndpointArtifactScanner scanner,
                                      ApiEndpointPersistenceService persistenceService,
                                      ApiEndpointRepository repository) {
        this.scanner = scanner;
        this.persistenceService = persistenceService;
        this.repository = repository;
    }

    public void analyzeUploadedArtifact(String appId, MultipartFile file) throws Exception {
        File temp = Files.createTempFile("oat-api-endpoint-", "-" + safeName(file.getOriginalFilename())).toFile();
        try {
            file.transferTo(temp);
            analyzeArtifactFile(appId, temp);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    public void analyzeArtifactFile(String appId, File file) throws Exception {
        persistenceService.persist(appId, file.getName(), scanner.scanArtifact(file, file.getName()));
    }

    public List<ApiEndpointViewVo> listByAppId(String appId) {
        return repository.findByAppIdOrderByEndpointTypeAscUrlAsc(appId).stream().map(this::toView).toList();
    }

    private ApiEndpointViewVo toView(ApiEndpointIndex index) {
        ApiEndpointViewVo view = new ApiEndpointViewVo();
        view.setId(index.getId());
        view.setEndpointType(index.getEndpointType());
        view.setUrl(index.getUrl());
        view.setHttpMethod(index.getHttpMethod());
        view.setClassName(index.getClassName());
        view.setMethodName(index.getMethodName());
        view.setMethodDesc(index.getMethodDesc());
        view.setSourceType(index.getSourceType());
        view.setSourceName(index.getSourceName());
        view.setCovered(Boolean.TRUE.equals(index.getCovered()));
        view.setHitCount(index.getHitCount() == null ? 0 : index.getHitCount());
        view.setMergedSourceCount(index.getMergedSourceCount() == null ? 0 : index.getMergedSourceCount());
        view.setClassNameList(splitLines(index.getClassNames()));
        view.setMethodNameList(splitLines(index.getMethodNames()));
        view.setMethodDescList(splitLines(index.getMethodDescs()));
        view.setSourceTypeList(splitLines(index.getSourceTypeNames()));
        view.setSourceNameList(splitLines(index.getSourceNames()));
        return view;
    }

    private List<String> splitLines(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("\\R")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    private String safeName(String name) {
        return name == null ? "artifact.zip" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
