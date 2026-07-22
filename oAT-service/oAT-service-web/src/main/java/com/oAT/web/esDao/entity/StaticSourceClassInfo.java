package com.oAT.web.esDao.entity;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StaticSourceClassInfo implements Serializable {
    private String classId;
    private String className;
    /** Fully-qualified superclass name (e.g. "com.example.BaseService"). Null for java.lang.Object. */
    private String superName;
    /** Fully-qualified names of directly implemented interfaces. */
    private List<String> interfaces;
    /** Class-level annotation descriptors, e.g. ["Lorg/springframework/stereotype/Service;"]. */
    private List<String> classAnnotations;
    /** Field declarations: name → descriptor + annotations. */
    private Map<String, FieldInfo> fields;
    private Map<String, StaticSourceMethodInfo> methodMaps;
    private String sourceCode;

    public String getClassId() { return classId; }
    public void setClassId(String classId) { this.classId = classId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getSuperName() { return superName; }
    public void setSuperName(String superName) { this.superName = superName; }

    public List<String> getInterfaces() { return interfaces; }
    public void setInterfaces(List<String> interfaces) { this.interfaces = interfaces; }

    public List<String> getClassAnnotations() { return classAnnotations; }
    public void setClassAnnotations(List<String> classAnnotations) { this.classAnnotations = classAnnotations; }

    public Map<String, FieldInfo> getFields() { return fields; }
    public void setFields(Map<String, FieldInfo> fields) { this.fields = fields; }

    public Map<String, StaticSourceMethodInfo> getMethodMaps() { return methodMaps; }
    public void setMethodMaps(Map<String, StaticSourceMethodInfo> methodMaps) { this.methodMaps = methodMaps; }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldInfo implements Serializable {
        /** Field name. */
        private String name;
        /** ASM type descriptor, e.g. "Lcom/example/OrderService;". */
        private String descriptor;
        /** Annotation descriptors present on this field (e.g. "@Autowired", "@Value"). */
        private List<String> annotations;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescriptor() { return descriptor; }
        public void setDescriptor(String descriptor) { this.descriptor = descriptor; }

        public List<String> getAnnotations() { return annotations; }
        public void setAnnotations(List<String> annotations) { this.annotations = annotations; }

        /** Returns the binary class name of the field type extracted from the descriptor, or null if not a reference type. */
        public String referenceTypeName() {
            if (descriptor == null || !descriptor.startsWith("L") || !descriptor.endsWith(";")) return null;
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
    }
}
