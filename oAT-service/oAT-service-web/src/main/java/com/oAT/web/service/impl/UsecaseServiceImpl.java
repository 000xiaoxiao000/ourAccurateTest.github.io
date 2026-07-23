package com.oAT.web.service.impl;

import com.oAT.web.domain.usecase.UsecaseDirectoryDeletionService;
import com.oAT.web.domain.usecase.UsecaseViewMapper;
import com.oAT.web.persistence.CaseCenterRepository;
import com.oAT.web.persistence.entity.*;
import com.oAT.web.exceptions.DirtyDataException;
import com.oAT.web.service.UsecaseService;
import com.oAT.web.service.entity.DirectoryDeleteResult;
import com.oAT.web.service.entity.UsecaseDetailVo;
import com.oAT.web.service.entity.UsecaseDirectoryVo;
import com.oAT.web.service.entity.UsecaseVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class UsecaseServiceImpl implements UsecaseService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(UsecaseServiceImpl.class);

    @Autowired
    CaseCenterRepository centerRepository;
    @Autowired
    UsecaseViewMapper usecaseViewMapper;
    @Autowired
    UsecaseDirectoryDeletionService usecaseDirectoryDeletionService;

    @Override
    public UsecaseVo doAdd(String author, UsecaseVo usecaseParam) {

        Usecase usecase = new Usecase();
        // 设置基本信息
        BeanUtils.copyProperties(usecaseParam, usecase);
        usecase.setLastUpdateAuthor(author);
        usecase.setAuthors(new String[]{author});
        CaseCenterIndex caseCenterIndex = new CaseCenterIndex(usecase);
        CaseCenterIndex index = centerRepository.save(caseCenterIndex);
        return usecaseViewMapper.convertUsecase(index);
    }

    @Override
    public List<UsecaseVo> getUsecases(String projectId, String directory, String sort, String keyword) {
        Assert.notNull(projectId, "param 'projectId' must be not null");
        Assert.notNull(directory, "param 'directory' must be not null");

        if ("name".equals(sort)) {
            sort = "usecase.title.keyword";
        }

        List<CaseCenterIndex> list = centerRepository.findByUsecase_ProjectIdAndAndUsecase_Directory(projectId, directory, PageRequest.of(0, 100, Sort.Direction.DESC, sort));
        if (StringUtils.hasText(keyword)) {
            String normalizedKeyword = keyword.trim().toLowerCase();
            list = list.stream()
                    .filter(item -> item.getUsecase() != null && StringUtils.hasText(item.getUsecase().getTitle()))
                    .filter(item -> item.getUsecase().getTitle().toLowerCase().contains(normalizedKeyword))
                    .collect(Collectors.toList());
        }
        List<UsecaseVo> result = new ArrayList<>();
        for (CaseCenterIndex caseCenterIndex : list) {
            result.add(usecaseViewMapper.convertUsecase(caseCenterIndex));
        }
        return result;
    }

    @Override
    public UsecaseVo getUsecase(String projectId, String id) {
        Optional<CaseCenterIndex> optional = centerRepository.findById(id);
        Assert.isTrue(optional.isPresent(), "找不到指定用例 id=" + id);
        Assert.notNull(optional.get().getUsecase(), "not found usecase by id id=" + id);
        Assert.isTrue(optional.get().getUsecase().getProjectId().equals(projectId), "the usecase not belong to project Id=" + projectId);
        return usecaseViewMapper.convertUsecase(optional.get());
    }

    @Override
    public List<UsecaseVo> getUsecases(String projectId, String[] ids) {
        List<UsecaseVo> result = new ArrayList<>(ids.length);
        Iterable<CaseCenterIndex> cases = centerRepository.findAllById(Arrays.asList(ids));
        for (CaseCenterIndex aCase : cases) {
            Assert.isTrue(aCase.getUsecase().getProjectId().equals(projectId), "the usecase not belong to project Id=" + projectId);
            result.add(usecaseViewMapper.convertUsecase(aCase));
        }
        return result;
    }

    @Override
    public UsecaseDetailVo getUsecaseDetail(String projectId, String id) {
        Optional<CaseCenterIndex> optional = centerRepository.findById(id);
        Assert.isTrue(optional.isPresent(), "not found usecase by id id=" + id);
        Assert.notNull(optional.get().getUsecase(), "not found usecase by id id=" + id);
        Assert.isTrue(optional.get().getUsecase().getProjectId().equals(projectId), "the usecase not belong to project Id=" + projectId);
        return usecaseViewMapper.convertUsecaseDetail(optional.get());
    }

    /**
     * 创建用例目录
     *
     * @return
     */
    @Override
    public UsecaseDirectoryVo createFolder(String projectId, String parentId, String name) {
        UsecaseDirectory directory = new UsecaseDirectory();
        directory.setName(name);
        directory.setParentId(parentId);
        directory.setProjectId(projectId);
        directory.setChildId(new String[0]);
        CaseCenterIndex index = new CaseCenterIndex(directory);
        index = centerRepository.save(index);
        String currentId = index.getId();
        if (!"root".equalsIgnoreCase(parentId)) {
            index = centerRepository.findById(parentId).orElseThrow(() -> new IllegalArgumentException("找不到应用 parentId=" + parentId));
            String[] s1 = index.getDirectory().getChildId();
            int length1 = s1.length;
            String[] s2 = new String[length1 + 1];
            for (int i = 0; i < length1; i++) {
                s2[i] = s1[i];
            }
            s2[length1] = currentId;
            index.getDirectory().setChildId(s2);
        }
        index = centerRepository.save(index);
        return usecaseViewMapper.convertDirectory(index);
    }

    /**
     * 查找指定目录下的目录
     *
     * @param projectId
     * @param parentId
     * @return
     */
    @Override
    public List<UsecaseDirectoryVo> getDirectory(String projectId, String parentId) {
        List<CaseCenterIndex> list = centerRepository.findByDirectory_ProjectIdAndDirectory_ParentId(projectId, parentId);
        List<UsecaseDirectoryVo> result = new ArrayList<>();
        for (CaseCenterIndex caseCenterIndex : list) {
            result.add(usecaseViewMapper.convertDirectory(caseCenterIndex));
        }
        return result;
    }

    @Override
    public List<UsecaseDirectoryVo> getDirectoryTier(String projectId, String directory) {
        Assert.hasText(projectId, "param 'project' must be not null");
        Assert.hasText(directory, "param 'directory' must be not null");
        Assert.isTrue(!directory.equalsIgnoreCase("root"), "root node not exist tier");
        // 查找当前项目下所有目录信息
        List<CaseCenterIndex> list = centerRepository.findByDirectory_ProjectId(projectId);
        Map<String, UsecaseDirectoryVo> map = new HashMap<>();
        List<UsecaseDirectoryVo> result = new ArrayList<>();
        for (CaseCenterIndex caseCenterIndex : list) {
            map.put(caseCenterIndex.getId(), usecaseViewMapper.convertDirectory(caseCenterIndex));
        }
        UsecaseDirectoryVo vo = map.get(directory);
        result.add(vo);
        while (!vo.getParentId().equals("root")) {
            if (!map.containsKey(vo.getParentId())) {
                throw new DirtyDataException(String.format("Usecase directory id=%s name=%s parent id id not found", vo.getId(), vo.getName()));
            }
            vo = map.get(vo.getParentId());
            result.add(vo);
        }
        return result;
    }

    /**
     * 更新用例路径
     */
    @Override
    public void updateFolder(String id, String parentId, String name) {
        Optional<CaseCenterIndex> optional = centerRepository.findById(id);
        Assert.isTrue(optional.isPresent(), "not found usecase directory by directory id=" + id);
        CaseCenterIndex index = optional.get();
        index.getDirectory().setParentId(parentId);
        index.getDirectory().setName(name);
        index.setUpdateTime(new java.util.Date());
        centerRepository.save(index);
    }

    /**
     * 删除目录
     */
    @Override
    public Boolean delFolder(String projectId, String directoryId, String parentId, String name) {
        return usecaseDirectoryDeletionService.delFolder(directoryId, parentId);
    }

    /**
     * 更新用例
     *
     * @param author       更新作者
     * @param usecaseParam 更新的信息
     */
    @Override
    public void doUpdate(String author, UsecaseVo usecaseParam) {
        Optional<CaseCenterIndex> optional = centerRepository.findById(usecaseParam.getId());
        Assert.isTrue(optional.isPresent(), "not found usecase Id id=" + usecaseParam.getId());
        CaseCenterIndex index = optional.get();

        // 复制属性
        BeanUtils.copyProperties(usecaseParam, index.getUsecase(), "authors", "projectId", "id", "share");
        // 添加作者
        List<String> authorList = new ArrayList<>(Arrays.asList(Optional.ofNullable(index.getUsecase().getAuthors()).orElse(new String[0])));
        if (!authorList.contains(author)) {
            authorList.add(author);
        }
        index.getUsecase().setAuthors(authorList.toArray(new String[0]));
        index.getUsecase().setLastUpdateAuthor(author);
        // 更新修改时间
        index.setUpdateTime(new java.util.Date());
        centerRepository.save(index);
    }

    @Override
    public void setShareState(String projectId, String operator, String usecaseId, Boolean share) {
        Assert.hasText(projectId, "projectId不能为空");
        Assert.hasText(usecaseId, "用例ID不能为空");
        Optional<CaseCenterIndex> op = centerRepository.findById(usecaseId);
        Assert.isTrue(op.isPresent(), "找不到指定用例 id=" + usecaseId);
        CaseCenterIndex index = op.get();
        Assert.notNull(index.getUsecase(), "找不到指定用例 id=" + usecaseId);
        Usecase usecase = index.getUsecase();
        Assert.isTrue(projectId.equals(usecase.getProjectId()), "the usecase not belong to project Id=" + projectId);
        Boolean nextShare = Boolean.TRUE.equals(share);
        if (!nextShare.equals(usecase.getShare())) {
            usecase.setShare(nextShare);
            if (StringUtils.hasText(operator)) {
                usecase.setLastUpdateAuthor(operator);
                List<String> authorList = new ArrayList<>(Arrays.asList(Optional.ofNullable(usecase.getAuthors()).orElse(new String[0])));
                if (!authorList.contains(operator)) {
                    authorList.add(operator);
                }
                usecase.setAuthors(authorList.toArray(new String[0]));
            }
            index.setUpdateTime(new Date());
            centerRepository.save(index);
        }
    }

    /**
     * 基于ID删除指定用例
     *
     * @param projectId
     * @param id
     */
    @Override
    public void doDeleteUsecase(String projectId, String id) {
        Assert.hasText(projectId, "projectId不能为空");
        Assert.hasText(id, "用例ID不能为空");
        Optional<CaseCenterIndex> op = centerRepository.findById(id);
        if (!op.isPresent() || op.get().getUsecase() == null) {
            logger.warn("删除用例跳过，目标用例已不存在, projectId={}, usecaseId={}", projectId, id);
            return;
        }
        Assert.isTrue(op.get().getUsecase().getProjectId().equals(projectId), "the usecase not belong to project Id=" + projectId);
        centerRepository.deleteById(id);
    }

    @Override
    public int countUsecasesInDirectory(String projectId, String directoryId) {
        Assert.hasText(projectId, "param 'projectId' must be not null");
        Assert.hasText(directoryId, "param 'directoryId' must be not null");
        return usecaseDirectoryDeletionService.countUsecasesInDirectory(projectId, directoryId);
    }

    @Override
    public int countDirectoryDescendants(String projectId, String directoryId) {
        Assert.hasText(projectId, "param 'projectId' must be not null");
        Assert.hasText(directoryId, "param 'directoryId' must be not null");
        return usecaseDirectoryDeletionService.countDirectoryDescendants(projectId, directoryId);
    }

    @Override
    public int deleteDirectoryWithUsecases(String projectId, String directoryId, String parentId, String name) {
        Assert.hasText(projectId, "param 'projectId' must be not null");
        Assert.hasText(directoryId, "param 'directoryId' must be not null");
        Assert.hasText(parentId, "param 'parentId' must be not null");
        Assert.hasText(name, "param 'name' must be not null");

        return usecaseDirectoryDeletionService.deleteDirectoryWithUsecases(projectId, directoryId);
    }

    @Override
    public DirectoryDeleteResult previewDeleteDirectory(String projectId, String directoryId) {
        Assert.hasText(projectId, "param 'projectId' must be not null");
        Assert.hasText(directoryId, "param 'directoryId' must be not null");
        return usecaseDirectoryDeletionService.previewDeleteDirectory(projectId, directoryId);
    }

    @Override
    public DirectoryDeleteResult deleteDirectory(String projectId, String directoryId, String parentId, String name, boolean cascade) {
        Assert.hasText(projectId, "param 'projectId' must be not null");
        Assert.hasText(directoryId, "param 'directoryId' must be not null");
        Assert.hasText(parentId, "param 'parentId' must be not null");
        Assert.hasText(name, "param 'name' must be not null");

        return usecaseDirectoryDeletionService.deleteDirectory(projectId, directoryId, parentId, cascade);
    }

}
