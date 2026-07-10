package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.List;

public class ApiEndpointViewVo implements Serializable {
    private String id;
    private String endpointType;
    private String url;
    private String httpMethod;
    private String className;
    private String methodName;
    private String methodDesc;
    private String sourceType;
    private String sourceName;
    private boolean covered;
    private int hitCount;
    private int mergedSourceCount;
    private List<String> classNameList;
    private List<String> methodNameList;
    private List<String> methodDescList;
    private List<String> sourceTypeList;
    private List<String> sourceNameList;
    private List<String> endpointKeyParts;
    private List<UsecaseLinkVo> linkedUsecases;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEndpointType() { return endpointType; }
    public void setEndpointType(String endpointType) { this.endpointType = endpointType; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getMethodDesc() { return methodDesc; }
    public void setMethodDesc(String methodDesc) { this.methodDesc = methodDesc; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public boolean isCovered() { return covered; }
    public void setCovered(boolean covered) { this.covered = covered; }
    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }
    public int getMergedSourceCount() { return mergedSourceCount; }
    public void setMergedSourceCount(int mergedSourceCount) { this.mergedSourceCount = mergedSourceCount; }
    public List<String> getClassNameList() { return classNameList; }
    public void setClassNameList(List<String> classNameList) { this.classNameList = classNameList; }
    public List<String> getMethodNameList() { return methodNameList; }
    public void setMethodNameList(List<String> methodNameList) { this.methodNameList = methodNameList; }
    public List<String> getMethodDescList() { return methodDescList; }
    public void setMethodDescList(List<String> methodDescList) { this.methodDescList = methodDescList; }
    public List<String> getSourceTypeList() { return sourceTypeList; }
    public void setSourceTypeList(List<String> sourceTypeList) { this.sourceTypeList = sourceTypeList; }
    public List<String> getSourceNameList() { return sourceNameList; }
    public void setSourceNameList(List<String> sourceNameList) { this.sourceNameList = sourceNameList; }
    public List<String> getEndpointKeyParts() { return endpointKeyParts; }
    public void setEndpointKeyParts(List<String> endpointKeyParts) { this.endpointKeyParts = endpointKeyParts; }
    public List<UsecaseLinkVo> getLinkedUsecases() { return linkedUsecases; }
    public void setLinkedUsecases(List<UsecaseLinkVo> linkedUsecases) { this.linkedUsecases = linkedUsecases; }

    public static class UsecaseLinkVo implements Serializable {
        private String id;
        private String title;
        private String directory;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
    }
}
