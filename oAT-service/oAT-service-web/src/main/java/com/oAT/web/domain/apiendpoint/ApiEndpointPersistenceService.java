package com.oAT.web.domain.apiendpoint;

import com.oAT.web.esDao.ApiEndpointRepository;
import com.oAT.web.esDao.entity.ApiEndpointIndex;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ApiEndpointPersistenceService {

    private final ApiEndpointRepository apiEndpointRepository;

    public ApiEndpointPersistenceService(ApiEndpointRepository apiEndpointRepository) {
        this.apiEndpointRepository = apiEndpointRepository;
    }

    public void persist(String appId, String sourceName, Iterable<ApiEndpointArtifactScanner.EndpointRecord> records) {
        apiEndpointRepository.deleteByAppId(appId);
        Map<String, EndpointAggregate> aggregateMap = new LinkedHashMap<>();
        for (ApiEndpointArtifactScanner.EndpointRecord record : records) {
            aggregateMap.computeIfAbsent(record.mergeKey(), key -> new EndpointAggregate(record)).merge(record);
        }
        List<ApiEndpointIndex> indexes = new ArrayList<>();
        Date now = new Date();
        for (EndpointAggregate aggregate : aggregateMap.values()) {
            ApiEndpointIndex index = new ApiEndpointIndex();
            index.setId(UUID.randomUUID().toString());
            index.setAppId(appId);
            index.setSourceName(sourceName + " :: " + aggregate.primarySourceName());
            index.setSourceNames(joinValues(aggregate.sourceNames));
            index.setSourceType(aggregate.primarySourceType());
            index.setSourceTypeNames(joinValues(aggregate.sourceTypes));
            index.setEndpointType(aggregate.endpointType);
            index.setUrl(aggregate.url);
            index.setHttpMethod(aggregate.httpMethod);
            index.setClassName(aggregate.primaryClassName());
            index.setClassNames(joinValues(aggregate.classNames));
            index.setMethodName(aggregate.primaryMethodName());
            index.setMethodNames(joinValues(aggregate.methodNames));
            index.setMethodDesc(aggregate.primaryMethodDesc());
            index.setMethodDescs(joinValues(aggregate.methodDescs));
            index.setHitCount(0);
            index.setMergedSourceCount(aggregate.sourceNames.size());
            index.setCreateTime(now);
            index.setUpdateTime(now);
            indexes.add(index);
        }
        apiEndpointRepository.saveAll(indexes);
    }

    private String joinValues(Set<String> values) {
        return values.stream().filter(StringUtils::hasText).collect(Collectors.joining("\n"));
    }

    private static class EndpointAggregate {
        private final String endpointType;
        private final String url;
        private final String httpMethod;
        private final Set<String> classNames = new TreeSet<>();
        private final Set<String> methodNames = new TreeSet<>();
        private final Set<String> methodDescs = new TreeSet<>();
        private final Set<String> sourceTypes = new TreeSet<>();
        private final Set<String> sourceNames = new TreeSet<>();

        EndpointAggregate(ApiEndpointArtifactScanner.EndpointRecord record) {
            this.endpointType = record.endpointType;
            this.url = record.url;
            this.httpMethod = record.httpMethod;
            merge(record);
        }

        void merge(ApiEndpointArtifactScanner.EndpointRecord record) {
            if (StringUtils.hasText(record.className)) {
                classNames.add(record.className);
            }
            if (StringUtils.hasText(record.methodName)) {
                methodNames.add(record.methodName);
            }
            if (StringUtils.hasText(record.methodDesc)) {
                methodDescs.add(record.methodDesc);
            }
            if (StringUtils.hasText(record.sourceType)) {
                sourceTypes.add(record.sourceType);
            }
            if (StringUtils.hasText(record.sourceName)) {
                sourceNames.add(record.sourceName);
            }
        }

        String primaryClassName() { return classNames.isEmpty() ? "" : classNames.iterator().next(); }
        String primaryMethodName() { return methodNames.isEmpty() ? "" : methodNames.iterator().next(); }
        String primaryMethodDesc() { return methodDescs.isEmpty() ? "" : methodDescs.iterator().next(); }
        String primarySourceType() { return sourceTypes.isEmpty() ? "" : sourceTypes.iterator().next(); }
        String primarySourceName() { return sourceNames.isEmpty() ? "" : sourceNames.iterator().next(); }
    }
}
