package com.oAT.web.control.entity;

import java.io.Serializable;

public class LableVo implements Serializable {

    private String name;
    private String color;
    private boolean select;

    public LableVo() {
    }

    public LableVo(String name, String color, boolean select) {
        this.name = name;
        this.color = color;
        this.select = select;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public boolean isSelect() {
        return select;
    }

    public void setSelect(boolean select) {
        this.select = select;
    }

}
