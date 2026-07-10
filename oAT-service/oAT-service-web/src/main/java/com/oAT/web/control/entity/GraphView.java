package com.oAT.web.control.entity;

import java.util.List;

/**
 * Auto-generated: 2018-09-22 16:17:6
 *
 * @author bejson.com (i@bejson.com)
 * @website http://www.bejson.com/java2pojo/
 */
public class GraphView {

    private String title;
    private String traceId;
    private Boolean hasCodeLayer;
    private Nodes showDefaultNode;
    private List<Nodes> nodes;
    private List<Edges> edges;

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Boolean getHasCodeLayer() {
        return hasCodeLayer;
    }

    public void setHasCodeLayer(Boolean hasCodeLayer) {
        this.hasCodeLayer = hasCodeLayer;
    }

    public void setShowDefaultNode(Nodes showDefaultNode) {
        this.showDefaultNode = showDefaultNode;
    }

    public Nodes getShowDefaultNode() {
        return showDefaultNode;
    }

    public void setNodes(List<Nodes> nodes) {
        this.nodes = nodes;
    }

    public List<Nodes> getNodes() {
        return nodes;
    }

    public void setEdges(List<Edges> edges) {
        this.edges = edges;
    }

    public List<Edges> getEdges() {
        return edges;
    }

    public static class Nodes {
        private String id;
        private String title;
        private String subTitle;
        private String icon;
        private String state;
        private String tips;
        private String type;
        private Object data;


        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getSubTitle() {
            return subTitle;
        }

        public void setSubTitle(String subTitle) {
            this.subTitle = subTitle;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getTips() {
            return tips;
        }

        public void setTips(String tips) {
            this.tips = tips;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }
    }

    public static class Edges {

        private String from;
        private String to;
        private String label;
        private String title;
        private String description;
        private String type;
        private int count;

        public void setFrom(String from) {
            this.from = from;
        }

        public String getFrom() {
            return from;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getTo() {
            return to;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

}
