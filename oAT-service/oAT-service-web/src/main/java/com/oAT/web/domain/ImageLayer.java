package com.oAT.web.domain;

import java.util.List;
import java.util.UUID;

public interface ImageLayer {

    List<ImageElement> elements();

    default ImageElement buildDefaultNode(ImageData data) {
        ImageElement element = new ImageElement(data);
        element.group = "nodes";
        return element;
    }

    default ImageElement buildDefaultEdge(ImageData data) {
        ImageElement element = new ImageElement(data);
        element.group = "edges";
        return element;
    }

    default String generateTempId() {
        String id = UUID.nameUUIDFromBytes(UUID.randomUUID().toString().getBytes())
                .toString()
                .split("-")[0];
        return id;
    }

    default boolean isNodeElement(ImageElement element) {
        return element.group.equals("nodes");
    }

    default boolean isEdgeElement(ImageElement element) {
        return element.group.equals("edges");
    }

}
