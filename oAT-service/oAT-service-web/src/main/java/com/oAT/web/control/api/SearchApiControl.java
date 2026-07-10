package com.oAT.web.control.api;

import com.oAT.web.api.search.SearchApiPayloads.*;
import com.oAT.web.control.entity.ResultNotified;
import com.oAT.web.service.ProjectService;
import com.oAT.web.service.UsecaseSearchService;
import com.oAT.web.service.entity.CaseSearchResult;
import com.oAT.web.service.entity.ProjectVo;
import com.oAT.web.service.entity.SearchPage;
import com.oAT.web.service.entity.UserVo;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}/search")
public class SearchApiControl {

    private final UsecaseSearchService usecaseSearchService;
    private final ProjectService projectService;

    public SearchApiControl(UsecaseSearchService usecaseSearchService,
                            ProjectService projectService) {
        this.usecaseSearchService = usecaseSearchService;
        this.projectService = projectService;
    }

    @GetMapping("/keyword")
    public ResultNotified<SearchKeywordPayload> keyword(@PathVariable String projectId,
                                                        @SessionAttribute UserVo user,
                                                        @RequestParam String keyword,
                                                        @RequestParam(name = "type", required = false, defaultValue = "systemSnapshot") String type) {
        ensureProjectAccess(projectId, user);
        Assert.hasText(keyword, "keyword不能为空");

        SearchKeywordPayload payload = new SearchKeywordPayload();
        payload.setKeyword(keyword);
        SearchPage<CaseSearchResult> page = usecaseSearchService.doSearch(projectId, keyword);
        payload.setTotal(page.getTotal());
        payload.setResults((page.getContents() == null ? java.util.Collections.<CaseSearchResult>emptyList() : page.getContents())
                .stream().map(this::toUsecaseResult).collect(Collectors.toList()));
        return new ResultNotified<>(true, "搜索成功", payload);
    }

    private SearchKeywordResult toUsecaseResult(CaseSearchResult item) {
        SearchKeywordResult result = new SearchKeywordResult();
        result.setId(item.getId());
        result.setResultType("usecase");
        result.setTitle(StringUtils.hasText(item.getTitleFragment()) ? item.getTitleFragment() : item.getTitle());
        result.setPlainTitle(item.getTitle());
        result.setTitleFragment(item.getTitleFragment());
        result.setHeadImage(item.getHeadImage());
        result.setImagePath(StringUtils.hasText(item.getHeadImage()) ? "/r/" + item.getHeadImage() : "/images/image.png");
        result.setDescribeFragments(item.getContentFragments());
        result.setSqlContentFragments(item.getSqlContentFragments());
        result.setRemoteContentFragments(item.getRemoteContentFragments());
        result.setUpdateTimeText(item.getUpdateTime() == null ? null : item.getUpdateTime().toString());
        result.setDescription(item.getContentFragments() == null ? null : String.join("</br>", item.getContentFragments()));
        result.setTargetPath("/p/" + item.getProjectId() + "/usecases/" + item.getId());
        return result;
    }

    private ProjectVo ensureProjectAccess(String projectId, UserVo user) {
        ProjectVo project = projectService.getProjectByProjectIdAndMemberId(projectId, user.getId());
        Assert.notNull(project, "找不到指定项目,或者您没有该项目的访问权限");
        return project;
    }

}
