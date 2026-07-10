package com.oAT.web.service;

import com.oAT.web.service.entity.DirectoryDeleteResult;
import com.oAT.web.service.entity.UsecaseDetailVo;
import com.oAT.web.service.entity.UsecaseDirectoryVo;
import com.oAT.web.service.entity.UsecaseVo;

import java.util.List;


/**
 * 用例服务
 */
public interface UsecaseService {

    UsecaseVo doAdd(String author, UsecaseVo usecaseParam);

    List<UsecaseVo> getUsecases(String projectId, String directory, String sort, String keyword);

    UsecaseVo getUsecase(String projectId, String id);

    List<UsecaseVo> getUsecases(String projectId, String[] ids);

    UsecaseDetailVo getUsecaseDetail(String projectId, String id);

    /**
     * 创建用例目录
     */
    UsecaseDirectoryVo createFolder(String projectId, String parentId, String name);

    List<UsecaseDirectoryVo> getDirectory(String projectId, String parentId);

    List<UsecaseDirectoryVo> getDirectoryTier(String projectId, String directory);

    /**
     * 更新用例目录
     * @param id
     * @param parentId
     * @param name
     */
    void updateFolder(String id, String parentId, String name);

    /**
     * 删除用例目录
     */
    Boolean delFolder(String projectId,String id, String parentId,String name);

    /**
     * 更新用例
     *
     * @param author  更新作者
     * @param usecase 更新的信息
     */
    void doUpdate(String author, UsecaseVo usecase);

    void setShareState(String projectId, String operator, String usecaseId, Boolean share);

    /**
     * 基于ID删除指定用例
     *
     * @param projectId
     * @param id
     */
    void doDeleteUsecase(String projectId, String id);

    int countUsecasesInDirectory(String projectId, String directoryId);

    int countDirectoryDescendants(String projectId, String directoryId);

    int deleteDirectoryWithUsecases(String projectId, String directoryId, String parentId, String name);

    DirectoryDeleteResult previewDeleteDirectory(String projectId, String directoryId);

    DirectoryDeleteResult deleteDirectory(String projectId, String directoryId, String parentId, String name, boolean cascade);

}
