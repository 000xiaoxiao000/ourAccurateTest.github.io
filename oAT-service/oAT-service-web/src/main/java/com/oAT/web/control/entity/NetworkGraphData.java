package com.oAT.web.control.entity;

public class NetworkGraphData implements java.io.Serializable{

    Node[] nodes;
    Edge[] edges;

    public NetworkGraphData() {
    }

    public NetworkGraphData(Node[] nodes, Edge[] edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    public Node[] getNodes() {
        return nodes;
    }

    public void setNodes(Node[] nodes) {
        this.nodes = nodes;
    }


    public Edge[] getEdges() {
        return edges;
    }

    public void setEdges(Edge[] edges) {
        this.edges = edges;
    }

    public static class Node implements java.io.Serializable {
        private String id;
        private String type;
        private String label;
        private String backgroundImage;
        private String appId;

        public Node() {
        }

        public Node(String id, String type, String label) {
            this.id = id;
            this.type = type;
            this.label = label;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBackgroundImage() {
            return backgroundImage;
        }

        public void setBackgroundImage(String backgroundImage) {
            this.backgroundImage = backgroundImage;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }
    }

    public static class Edge implements java.io.Serializable {
        private String id;
        private String source;
        private String target;
        private String type;
        private String label;
        private String action;

        public Edge() {
        }


        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }

}
