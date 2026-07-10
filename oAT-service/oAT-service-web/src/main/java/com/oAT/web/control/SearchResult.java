package com.oAT.web.control;

import java.util.ArrayList;
import java.util.List;

public class SearchResult {

    public List<Result> results = new ArrayList<>();

    public void addResult(Result result) {
        results.add(result);
    }

    public static class Result {
        public String id;
        public String title;
        public String description;
        public String price = "";
        public String image;

        public Result(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

}
