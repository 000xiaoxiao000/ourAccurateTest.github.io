package com.oAT.web.service;

import com.oAT.web.service.entity.CaseSearchResult;
import com.oAT.web.service.entity.SearchPage;
import com.oAT.web.service.entity.TableToUsecase;
import com.oAT.web.service.entity.UsecaseVo;

import java.util.List;

public interface UsecaseSearchService {

    SearchPage<CaseSearchResult> doSearch(String projectId, String keyWords);

    /**
     * 基于代码方法查找关联用例
     * @param projectId
     * @param srcMethods
     * @return
     */
    List<UsecaseVo> getBySrcMethod(String projectId, String... srcMethods);

    // 基于类名称match_phrase（短语匹配）进行查找
    List<UsecaseVo> getBySrcClass(String projectId, String srcClass);


    /**
     * 基于 表结构查找相关用例
     * @param projectId
     * @param databaseName
     * @param tableName
     * @return
     */
    List<TableToUsecase> getByTable(String projectId, String databaseName , String tableName);

}
