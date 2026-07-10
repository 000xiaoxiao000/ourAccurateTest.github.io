package com.oAT.web.control.entity;

public class GraphNode {

    private String id;
    private String name;
    private GraphNodeType type;
    private String ip;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public GraphNodeType getType() {
        return type;
    }

    public void setType(GraphNodeType type) {
        this.type = type;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public enum GraphNodeType {
        CLIENT("desktop"), APPLICATION("server"), DATABASE("DATABASE");

        private String icon;

        GraphNodeType(String icon) {
            this.icon = icon;
        }

        public String getIcon() {
            return icon;
        }
    }

}
