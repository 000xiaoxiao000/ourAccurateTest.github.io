package com.oAT.web.common.compare;

import java.io.Serializable;
import java.util.List;

public class CompareMethod implements Serializable {
    private int access;
    private String name;
    private String desc;
    private String body;
    private List<Object> texts;
    private int firstLine = -1;
    private int lastLine = -1;

    public CompareMethod() {
    }

    public CompareMethod(int access,String name,String desc){
        this.access = access;
        this.name = name;
        this.desc = desc;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public List<Object> getTexts() {
        return texts;
    }

    public void setTexts(List<Object> texts) {
        this.texts = texts;
    }

    public int getFirstLine() {
        return firstLine;
    }

    public void setFirstLine(int firstLine) {
        this.firstLine = firstLine;
    }

    public int getLastLine() {
        return lastLine;
    }

    public void setLastLine(int lastLine) {
        this.lastLine = lastLine;
    }
}
