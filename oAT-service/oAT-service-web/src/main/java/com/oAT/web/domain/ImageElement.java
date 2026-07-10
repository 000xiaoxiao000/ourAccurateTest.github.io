package com.oAT.web.domain;

public class ImageElement {

    public ImageData data;
    public Position position = new Position();
    public String group;
    public Boolean removed = false;
    public Boolean selected = false;
    public Boolean selectable = true;
    public Boolean locked = false;
    public Boolean grabbed = false;
    public Boolean grabbable = true;
    public String[] classes;

    public ImageElement(ImageData data) {
        this.data = data;
    }

    public static class Position {
        public int x = 0;
        public int y = 0;
    }
}
