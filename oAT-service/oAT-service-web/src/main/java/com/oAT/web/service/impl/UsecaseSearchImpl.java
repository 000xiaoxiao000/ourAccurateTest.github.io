package com.oAT.web.service.impl;

import com.oAT.web.persistence.CaseCenterRepository;
import com.oAT.web.persistence.entity.CaseCenterIndex;
import com.oAT.web.persistence.entity.Usecase;
import com.oAT.web.service.UsecaseSearchService;
import com.oAT.web.service.entity.CaseSearchResult;
import com.oAT.web.service.entity.SearchPage;
import com.oAT.web.service.entity.TableToUsecase;
import com.oAT.web.service.entity.UsecaseVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class UsecaseSearchImpl implements UsecaseSearchService {

    @Autowired
    private CaseCenterRepository caseCenterRepository;

    @Override
    public SearchPage<CaseSearchResult> doSearch(String projectId, String keyWords) {
        String normalizedKeyword = normalize(keyWords);
        List<CaseSearchResult> results = caseCenterRepository.findByUsecase_ProjectId(projectId).stream()
                .filter(index -> index != null && index.getUsecase() != null)
                .filter(index -> !StringUtils.hasText(normalizedKeyword) || matchesKeyword(index.getUsecase(), normalizedKeyword))
                .map(this::toCaseSearchResult)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        SearchPage<CaseSearchResult> searchPage = new SearchPage<>();
        searchPage.setContents(results);
        searchPage.setTotal(results.size());
        return searchPage;
    }

    private CaseSearchResult toCaseSearchResult(CaseCenterIndex content) {
        if (content == null || content.getUsecase() == null) {
            return null;
        }
        CaseSearchResult result = new CaseSearchResult();
        result.setId(content.getId());
        result.setProjectId(content.getUsecase().getProjectId());
        result.setTitle(content.getUsecase().getTitle());
        result.setUpdateTime(content.getUpdateTime());
        if (content.getUsecase().getHeadImage() != null) {
            result.setHeadImage(content.getUsecase().getHeadImage());
        }
        return result;
    }

    @Override
    public List<UsecaseVo> getBySrcMethod(String projectId, String... srcMethods) {
        Assert.notNull(projectId, "参数projectId 不能为空");
        Assert.notNull(srcMethods, "srcMethod 不能为空");
        LinkedHashSet<String> normalizedMethods = Arrays.stream(srcMethods)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(method -> !method.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedMethods.isEmpty()) {
            return new ArrayList<>();
        }
        return queryUsecases(projectId).stream()
                .filter(index -> containsAnySrcStack(index.getUsecase(), normalizedMethods))
                .map(this::toUsecaseVo)
                .collect(Collectors.toList());
    }

    @Override
    public List<UsecaseVo> getBySrcClass(String projectId, String srcClass) {
        Assert.notNull(projectId, "参数projectId 不能为空");
        Assert.notNull(srcClass, "srcClass 不能为空");
        String normalizedSrcClass = srcClass.substring(srcClass.indexOf("/") + 1);
        String keyword = normalize(normalizedSrcClass);
        return queryUsecases(projectId).stream()
                .filter(index -> containsSrcStackKeyword(index.getUsecase(), keyword))
                .map(this::toUsecaseVo)
                .collect(Collectors.toList());
    }

    @Override
    public List<TableToUsecase> getByTable(String projectId, String databaseName, String tableName) {
        return new ArrayList<>();
    }

    private List<CaseCenterIndex> queryUsecases(String projectId) {
        return caseCenterRepository.findByUsecase_ProjectId(projectId).stream()
                .filter(index -> index != null && index.getUsecase() != null)
                .collect(Collectors.toList());
    }

    private boolean matchesKeyword(Usecase usecase, String keyword) {
        return contains(usecase.getTitle(), keyword)
                || contains(usecase.getContent(), keyword)
                || containsAny(usecase.getSrcStack(), keyword);
    }

    private boolean containsAnySrcStack(Usecase usecase, LinkedHashSet<String> methods) {
        if (usecase == null || usecase.getSrcStack() == null) {
            return false;
        }
        for (String srcStack : usecase.getSrcStack()) {
            String normalizedStack = normalize(srcStack);
            for (String method : methods) {
                String normalizedMethod = normalize(method);
                if (normalizedStack.equals(normalizedMethod)
                        || normalizedStack.startsWith(normalizedMethod + "(")
                        || normalizedStack.startsWith(normalizedMethod + " ")
                        || normalizedStack.contains(normalizedMethod + " ")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsSrcStackKeyword(Usecase usecase, String keyword) {
        return containsAny(usecase == null ? null : usecase.getSrcStack(), keyword);
    }

    private boolean containsAny(String[] values, String keyword) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (contains(value, keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(String value, String keyword) {
        return StringUtils.hasText(value) && StringUtils.hasText(keyword) && normalize(value).contains(keyword);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private UsecaseVo toUsecaseVo(CaseCenterIndex caseCenterIndex) {
        UsecaseVo usecaseVo = new UsecaseVo();
        BeanUtils.copyProperties(caseCenterIndex.getUsecase(), usecaseVo);
        usecaseVo.setId(caseCenterIndex.getId());
        usecaseVo.setUpdateTime(caseCenterIndex.getUpdateTime());
        return usecaseVo;
    }
}
