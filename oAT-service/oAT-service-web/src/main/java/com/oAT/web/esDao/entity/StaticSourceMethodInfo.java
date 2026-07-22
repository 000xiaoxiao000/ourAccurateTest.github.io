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
    private List<InvocationInfo> invocations;
    /** Field accesses (GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC) made inside this method. */
    private List<FieldAccessInfo> fieldAccesses;
    /** Method-level annotation descriptors, e.g. ["Lorg/springframework/web/bind/annotation/GetMapping;"]. */
    private List<String> methodAnnotations;

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

    public List<InvocationInfo> getInvocations() {
        return invocations;
    }

    public void setInvocations(List<InvocationInfo> invocations) {
        this.invocations = invocations;
    }

    @JsonSetter("invokers")
    public void setLegacyInvokers(List<InvocationInfo> invokers) {
        if (this.invocations == null || this.invocations.isEmpty()) {
            this.invocations = invokers;
        }
    }

    public List<FieldAccessInfo> getFieldAccesses() {
        return fieldAccesses;
    }

    public void setFieldAccesses(List<FieldAccessInfo> fieldAccesses) {
        this.fieldAccesses = fieldAccesses;
    }

    public List<String> getMethodAnnotations() {
        return methodAnnotations;
    }

    public void setMethodAnnotations(List<String> methodAnnotations) {
        this.methodAnnotations = methodAnnotations;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldAccessInfo implements Serializable {
        /** Fully-qualified owner class name of the field being accessed. */
        private String owner;
        /** Field name. */
        private String name;
        /** ASM type descriptor of the field. */
        private String descriptor;
        /**
         * ASM opcode:
         *   GETSTATIC=178, PUTSTATIC=179, GETFIELD=180, PUTFIELD=181
         */
        private Integer opcode;

        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner == null ? null : owner.replace('/', '.'); }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescriptor() { return descriptor; }
        public void setDescriptor(String descriptor) { this.descriptor = descriptor; }

        public Integer getOpcode() { return opcode; }
        public void setOpcode(Integer opcode) { this.opcode = opcode; }

        public boolean isWrite() { return opcode != null && (opcode == 179 || opcode == 181); }
        public boolean isRead()  { return opcode != null && (opcode == 178 || opcode == 180); }

        /** Returns the binary class name of the field type, or null if not a reference type. */
        public String referenceTypeName() {
            if (descriptor == null || !descriptor.startsWith("L") || !descriptor.endsWith(";")) return null;
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InvocationInfo implements Serializable {
        private String owner;
        private String name;
        private String descriptor;
        private String desc;
        private Integer opcode;

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public void setDescriptor(String descriptor) {
            this.descriptor = descriptor;
        }

        public String getDesc() {
            return desc;
        }

        public void setDesc(String desc) {
            this.desc = desc;
        }

        public Integer getOpcode() {
            return opcode;
        }

        public void setOpcode(Integer opcode) {
            this.opcode = opcode;
        }

        public String descriptorValue() {
            return descriptor != null ? descriptor : desc;
        }
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
