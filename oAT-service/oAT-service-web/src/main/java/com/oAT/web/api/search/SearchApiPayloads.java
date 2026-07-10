package com.oAT.web.api.search;

import java.util.List;


public final class SearchApiPayloads {

    private SearchApiPayloads() {
    }

    public static class SearchKeywordPayload {
        private String keyword;
        private long total;
        private List<SearchKeywordResult> results;

        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public List<SearchKeywordResult> getResults() { return results; }
        public void setResults(List<SearchKeywordResult> results) { this.results = results; }
    }

    public static class SearchKeywordResult {
        private String id;
        private String appId;
        private String resultType;
        private String title;
        private String plainTitle;
        private String titleFragment;
        private String subTitle;
        private String headImage;
        private String imagePath;
        private String description;
        private String[] describeFragments;
        private String[] sqlContentFragments;
        private String[] remoteContentFragments;
        private String directoryPath;
        private String updateTimeText;
        private String targetPath;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getResultType() { return resultType; }
        public void setResultType(String resultType) { this.resultType = resultType; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getPlainTitle() { return plainTitle; }
        public void setPlainTitle(String plainTitle) { this.plainTitle = plainTitle; }
        public String getTitleFragment() { return titleFragment; }
        public void setTitleFragment(String titleFragment) { this.titleFragment = titleFragment; }
        public String getSubTitle() { return subTitle; }
        public void setSubTitle(String subTitle) { this.subTitle = subTitle; }
        public String getHeadImage() { return headImage; }
        public void setHeadImage(String headImage) { this.headImage = headImage; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String imagePath) { this.imagePath = imagePath; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String[] getDescribeFragments() { return describeFragments; }
        public void setDescribeFragments(String[] describeFragments) { this.describeFragments = describeFragments; }
        public String[] getSqlContentFragments() { return sqlContentFragments; }
        public void setSqlContentFragments(String[] sqlContentFragments) { this.sqlContentFragments = sqlContentFragments; }
        public String[] getRemoteContentFragments() { return remoteContentFragments; }
        public void setRemoteContentFragments(String[] remoteContentFragments) { this.remoteContentFragments = remoteContentFragments; }
        public String getDirectoryPath() { return directoryPath; }
        public void setDirectoryPath(String directoryPath) { this.directoryPath = directoryPath; }
        public String getUpdateTimeText() { return updateTimeText; }
        public void setUpdateTimeText(String updateTimeText) { this.updateTimeText = updateTimeText; }
        public String getTargetPath() { return targetPath; }
        public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
    }
}
