package com.oAT.web.esDao.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StaticSourceMethodInfo implements Serializable {
    private String methodName;
    private String methodDesc;
    private List<Integer> methodLineNumberMap;
    private List<Integer> branchLineNumberSet;
    private Map<String, List<Integer>> branchLineAndTargetProbeMap;
    private Integer totalBranchCount;
    private Integer cyclomaticComplexityMap;
    private Boolean recursiveMap;
    private Boolean asyncMethodMap;

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getMethodDesc() {
        return methodDesc;
    }

    public void setMethodDesc(String methodDesc) {
        this.methodDesc = methodDesc;
    }

    public List<Integer> getMethodLineNumberMap() {
        return methodLineNumberMap;
    }

    public void setMethodLineNumberMap(List<Integer> methodLineNumberMap) {
        this.methodLineNumberMap = methodLineNumberMap;
    }

    public List<Integer> getBranchLineNumberSet() {
        return branchLineNumberSet;
    }

    public void setBranchLineNumberSet(List<Integer> branchLineNumberSet) {
        this.branchLineNumberSet = branchLineNumberSet;
    }

    public Map<String, List<Integer>> getBranchLineAndTargetProbeMap() {
        return branchLineAndTargetProbeMap;
    }

    public void setBranchLineAndTargetProbeMap(Map<String, List<Integer>> branchLineAndTargetProbeMap) {
        this.branchLineAndTargetProbeMap = branchLineAndTargetProbeMap;
    }

    @JsonSetter("branchLineAndTargetProbeMap")
    public void setBranchLineAndTargetProbeMapNode(JsonNode branchLineAndTargetProbeMapNode) {
        this.branchLineAndTargetProbeMap = normalizeIntegerMap(branchLineAndTargetProbeMapNode);
    }

    @JsonSetter("branchLineAndConditionNumberMap")
    public void setLegacyBranchLineAndConditionNumberMapNode(JsonNode branchLineAndConditionNumberMapNode) {
        if (this.branchLineAndTargetProbeMap == null || this.branchLineAndTargetProbeMap.isEmpty()) {
            this.branchLineAndTargetProbeMap = normalizeIntegerMap(branchLineAndConditionNumberMapNode);
        }
    }

    public Integer getTotalBranchCount() {
        return totalBranchCount;
    }

    public void setTotalBranchCount(Integer totalBranchCount) {
        this.totalBranchCount = totalBranchCount;
    }

    public Integer getCyclomaticComplexityMap() {
        return cyclomaticComplexityMap;
    }

    public void setCyclomaticComplexityMap(Integer cyclomaticComplexityMap) {
        this.cyclomaticComplexityMap = cyclomaticComplexityMap;
    }

    public Boolean getRecursiveMap() {
        return recursiveMap;
    }

    public void setRecursiveMap(Boolean recursiveMap) {
        this.recursiveMap = recursiveMap;
    }

    public Boolean getAsyncMethodMap() {
        return asyncMethodMap;
    }

    public void setAsyncMethodMap(Boolean asyncMethodMap) {
        this.asyncMethodMap = asyncMethodMap;
    }

    private Map<String, List<Integer>> normalizeIntegerMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, List<Integer>> normalized = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            normalized.put(entry.getKey(), extractIntegerList(entry.getValue()));
        }
        return normalized;
    }

    private List<Integer> extractIntegerList(JsonNode node) {
        TreeSet<Integer> values = new TreeSet<>();
        collectIntegerValues(node, values);
        return new ArrayList<>(values);
    }

    private void collectIntegerValues(JsonNode node, TreeSet<Integer> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isInt() || node.isLong()) {
            values.add(node.asInt());
            return;
        }
        if (node.isTextual()) {
            try {
                values.add(Integer.parseInt(node.asText()));
            } catch (NumberFormatException ignore) {
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectIntegerValues(item, values);
            }
            return;
        }
        if (node.isObject()) {
            if (node.has("@items")) {
                collectIntegerValues(node.get("@items"), values);
                return;
            }
            if (node.has("@e")) {
                collectIntegerValues(node.get("@e"), values);
                return;
            }
            int beforeSize = values.size();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            boolean allNumericFieldNames = true;
            List<Integer> numericFieldNames = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                collectIntegerValues(entry.getValue(), values);
                try {
                    numericFieldNames.add(Integer.parseInt(entry.getKey()));
                } catch (NumberFormatException ex) {
                    allNumericFieldNames = false;
                }
            }
            if (values.size() == beforeSize && allNumericFieldNames) {
                values.addAll(numericFieldNames);
            }
        }
    }
}
