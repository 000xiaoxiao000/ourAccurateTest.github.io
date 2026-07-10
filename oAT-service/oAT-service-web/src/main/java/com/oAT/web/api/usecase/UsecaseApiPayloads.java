package com.oAT.web.api.usecase;

import com.oAT.web.api.common.ApiSummaries.*;
import com.oAT.web.esDao.entity.LabelGroup;
import com.oAT.web.service.entity.SimpleRelationOption;
import com.oAT.web.service.entity.UsecaseDetailVo;
import com.oAT.web.service.entity.UsecaseDirectoryVo;
import com.oAT.web.service.entity.UsecaseVo;

import java.util.List;
import java.util.Map;

public final class UsecaseApiPayloads {

    private UsecaseApiPayloads() {
    }

    public static class UsecaseListPayload {
        private String currentDirectory;
        private String currentDirectoryName;
        private String sort;
        private String keyword;
        private List<UsecaseVo> usecases;
        private List<UsecaseDirectoryVo> directories;
        private List<UsecaseDirectoryVo> directoryTiers;
        private Map<String, String> maintainerNameMap;
        private List<AppSummary> apps;
        private String currentUserRole;

        public String getCurrentDirectory() { return currentDirectory; }
        public void setCurrentDirectory(String currentDirectory) { this.currentDirectory = currentDirectory; }
        public String getCurrentDirectoryName() { return currentDirectoryName; }
        public void setCurrentDirectoryName(String currentDirectoryName) { this.currentDirectoryName = currentDirectoryName; }
        public String getSort() { return sort; }
        public void setSort(String sort) { this.sort = sort; }
        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public List<UsecaseVo> getUsecases() { return usecases; }
        public void setUsecases(List<UsecaseVo> usecases) { this.usecases = usecases; }
        public List<UsecaseDirectoryVo> getDirectories() { return directories; }
        public void setDirectories(List<UsecaseDirectoryVo> directories) { this.directories = directories; }
        public List<UsecaseDirectoryVo> getDirectoryTiers() { return directoryTiers; }
        public void setDirectoryTiers(List<UsecaseDirectoryVo> directoryTiers) { this.directoryTiers = directoryTiers; }
        public Map<String, String> getMaintainerNameMap() { return maintainerNameMap; }
        public void setMaintainerNameMap(Map<String, String> maintainerNameMap) { this.maintainerNameMap = maintainerNameMap; }
        public List<AppSummary> getApps() { return apps; }
        public void setApps(List<AppSummary> apps) { this.apps = apps; }
        public String getCurrentUserRole() { return currentUserRole; }
        public void setCurrentUserRole(String currentUserRole) { this.currentUserRole = currentUserRole; }
    }

    public static class UsecaseBootstrapPayload {
        private String currentDirectory;
        private String currentDirectoryName;
        private List<LabelGroup.Label> labels;
        private UsecaseVo usecase;
        private List<String> selectedLabelNames;
        private String defectsText;
        private String prdRequirementsText;
        private String currentUserRole;

        public String getCurrentDirectory() { return currentDirectory; }
        public void setCurrentDirectory(String currentDirectory) { this.currentDirectory = currentDirectory; }
        public String getCurrentDirectoryName() { return currentDirectoryName; }
        public void setCurrentDirectoryName(String currentDirectoryName) { this.currentDirectoryName = currentDirectoryName; }
        public List<LabelGroup.Label> getLabels() { return labels; }
        public void setLabels(List<LabelGroup.Label> labels) { this.labels = labels; }
        public UsecaseVo getUsecase() { return usecase; }
        public void setUsecase(UsecaseVo usecase) { this.usecase = usecase; }
        public List<String> getSelectedLabelNames() { return selectedLabelNames; }
        public void setSelectedLabelNames(List<String> selectedLabelNames) { this.selectedLabelNames = selectedLabelNames; }
        public String getDefectsText() { return defectsText; }
        public void setDefectsText(String defectsText) { this.defectsText = defectsText; }
        public String getPrdRequirementsText() { return prdRequirementsText; }
        public void setPrdRequirementsText(String prdRequirementsText) { this.prdRequirementsText = prdRequirementsText; }
        public String getCurrentUserRole() { return currentUserRole; }
        public void setCurrentUserRole(String currentUserRole) { this.currentUserRole = currentUserRole; }
    }

    public static class UsecaseDetailPayload {
        private UsecaseDetailVo usecase;
        private UserSummary lastUpdateAuthor;
        private List<LabelGroup.Label> labels;
        private List<SimpleRelationOption> defects;
        private List<SimpleRelationOption> prdRequirements;
        private String contentHtml;
        private String currentUserRole;

        public UsecaseDetailVo getUsecase() { return usecase; }
        public void setUsecase(UsecaseDetailVo usecase) { this.usecase = usecase; }
        public UserSummary getLastUpdateAuthor() { return lastUpdateAuthor; }
        public void setLastUpdateAuthor(UserSummary lastUpdateAuthor) { this.lastUpdateAuthor = lastUpdateAuthor; }
        public List<LabelGroup.Label> getLabels() { return labels; }
        public void setLabels(List<LabelGroup.Label> labels) { this.labels = labels; }
        public List<SimpleRelationOption> getDefects() { return defects; }
        public void setDefects(List<SimpleRelationOption> defects) { this.defects = defects; }
        public List<SimpleRelationOption> getPrdRequirements() { return prdRequirements; }
        public void setPrdRequirements(List<SimpleRelationOption> prdRequirements) { this.prdRequirements = prdRequirements; }
        public String getContentHtml() { return contentHtml; }
        public void setContentHtml(String contentHtml) { this.contentHtml = contentHtml; }
        public String getCurrentUserRole() { return currentUserRole; }
        public void setCurrentUserRole(String currentUserRole) { this.currentUserRole = currentUserRole; }
    }

    public static class SaveUsecaseRequest {
        private String id;
        private String title;
        private String headImage;
        private String content;
        private String directory;
        private List<String> labels;
        private String defectsText;
        private String prdRequirementsText;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getHeadImage() { return headImage; }
        public void setHeadImage(String headImage) { this.headImage = headImage; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public List<String> getLabels() { return labels; }
        public void setLabels(List<String> labels) { this.labels = labels; }
        public String getDefectsText() { return defectsText; }
        public void setDefectsText(String defectsText) { this.defectsText = defectsText; }
        public String getPrdRequirementsText() { return prdRequirementsText; }
        public void setPrdRequirementsText(String prdRequirementsText) { this.prdRequirementsText = prdRequirementsText; }
    }

    public static class ShareUsecaseRequest {
        private Boolean share;

        public Boolean getShare() { return share; }
        public void setShare(Boolean share) { this.share = share; }
    }

    public static class CreateDirectoryRequest {
        private String parentId;
        private String name;

        public String getParentId() { return parentId; }
        public void setParentId(String parentId) { this.parentId = parentId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class RenameDirectoryRequest {
        private String parentId;
        private String name;

        public String getParentId() { return parentId; }
        public void setParentId(String parentId) { this.parentId = parentId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class DeleteDirectoryRequest {
        private String parentId;
        private String name;
        private Boolean deleteUsecases;

        public String getParentId() { return parentId; }
        public void setParentId(String parentId) { this.parentId = parentId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Boolean getDeleteUsecases() { return deleteUsecases; }
        public void setDeleteUsecases(Boolean deleteUsecases) { this.deleteUsecases = deleteUsecases; }
    }
}
