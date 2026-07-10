package com.oAT.web.esDao.entity;


import java.io.Serializable;
import java.util.Map;

public class StaticSourceClassInfo implements Serializable {
    private String classId;
    private String className;
    private Map<String, StaticSourceMethodInfo> methodMaps;
    private String sourceCode;

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public Map<String, StaticSourceMethodInfo> getMethodMaps() {
        return methodMaps;
    }

    public void setMethodMaps(Map<String, StaticSourceMethodInfo> methodMaps) {
        this.methodMaps = methodMaps;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }
}
