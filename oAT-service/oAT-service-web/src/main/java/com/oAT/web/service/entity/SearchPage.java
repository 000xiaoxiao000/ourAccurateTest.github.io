package com.oAT.web.service.entity;

import java.io.Serializable;
import java.util.List;

public class SearchPage<T> implements Serializable {
    private List<T> contents;
    private long total;
    private int pageSzie;
    private int pageIndex;

    public List<T> getContents() {
        return contents;
    }

    public void setContents(List<T> contents) {
        this.contents = contents;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPageSzie() {
        return pageSzie;
    }

    public void setPageSzie(int pageSzie) {
        this.pageSzie = pageSzie;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }
}
