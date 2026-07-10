package com.oAT.web.common.compare;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class CompareClass implements Serializable {
    private int minorVersion;
    private String name;
    private int access;
    private String md5Signature;
    private List<CompareMethod> methods= new ArrayList<>();

    public int getMinorVersion() {
        return minorVersion;
    }

    public void setMinorVersion(int minorVersion) {
        this.minorVersion = minorVersion;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAccess() {
        return access;
    }

    public void setAccess(int access) {
        this.access = access;
    }

    public String getMd5Signature() {
        return md5Signature;
    }

    public void setMd5Signature(String md5Signature) {
        this.md5Signature = md5Signature;
    }

    public List<CompareMethod> getMethods() {
        return methods;
    }

    public void setMethods(List<CompareMethod> methods) {
        this.methods = methods;
    }

    public void add(CompareMethod method){methods.add(method);}
}
