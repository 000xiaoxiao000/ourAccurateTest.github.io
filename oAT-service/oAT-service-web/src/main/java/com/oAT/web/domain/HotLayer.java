package com.oAT.web.domain;

import java.util.List;

public class HotLayer implements ImageLayer {

    private final List<ImageElement> elements;

    public HotLayer(List<ImageElement> elements) {
        this.elements = elements;
    }

    @Override
    public List<ImageElement> elements() {
        return elements;
    }
}
