package com.oAT.web.common.compare;

import java.util.ArrayList;
import java.util.List;

public class CompareResult {
    private String className;
    private Model model;
    private List<Method> methods = new ArrayList<>();

    public CompareResult() {
    }

    public CompareResult(String className) {
        this.className = className;
    }

    public CompareResult(String className, Model model) {
        this.className = className;
        this.model = model;
    }

    public String getClassName() {
        return className;
    }

    public Method[] getMethods() {
        return methods.toArray(new Method[0]);
    }

    public void removeMethodsIf(java.util.function.Predicate<Method> predicate) {
        methods.removeIf(predicate);
    }

    public void add(String name, String desc, Model model) {
        methods.add(new Method(name, desc, model));
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public class Method {
        private String name;
        private String desc;
        private Model model;

        public Method(String name, String desc, Model model) {
            this.name = name;
            this.desc = desc;
            this.model = model;
        }

        public String getName() {
            return name;
        }

        public String getDesc() {
            return desc;
        }

        public Model getModel() {
            return model;
        }

        public String getFullName() {
            return className + " " + name + " " + desc;
        }

        @Override
        public String toString() {
            return "Method{" + "name='" + name + '\'' + ", desc='" + desc + '\'' + ", model=" + model + '}';
        }
    }

    public enum Model {
        //新增，修改，删除，相同的
        add, update, delete, same
    }
}
