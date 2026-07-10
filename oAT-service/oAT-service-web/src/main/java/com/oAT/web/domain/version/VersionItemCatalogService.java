package com.oAT.web.domain.version;

import com.oAT.web.esDao.VersionCenterRepository;
import com.oAT.web.esDao.entity.VersionCenterIndex;
import com.oAT.web.esDao.entity.VersionItem;
import com.oAT.web.service.ResourceService;
import com.oAT.web.service.entity.VersionItemVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class VersionItemCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(VersionItemCatalogService.class);

    private final VersionCenterRepository versionCenterRepository;
    private final ResourceService resourceService;
    private final VersionItemViewService versionItemViewService;

    public VersionItemCatalogService(VersionCenterRepository versionCenterRepository,
                                     ResourceService resourceService,
                                     VersionItemViewService versionItemViewService) {
        this.versionCenterRepository = versionCenterRepository;
        this.resourceService = resourceService;
        this.versionItemViewService = versionItemViewService;
    }

    public void addVersionItem(VersionItemVo itemVo) {
        Assert.notNull(itemVo, "参数'itemVo'不能为空");
        Assert.notNull(itemVo.getProgramFile(), "参数'itemVo.programFile'不能为空");
        Assert.hasText(itemVo.getVersionNumber(), "参数'itemVo.versionNumber'不能为空");

        VersionItem item = new VersionItem();
        BeanUtils.copyProperties(itemVo, item);
        VersionCenterIndex versionCenterIndex = versionCenterRepository.save(new VersionCenterIndex(item));
        versionItemViewService.convertVersionItem(versionCenterIndex);
    }

    public List<VersionItemVo> getVersionItemList(String projectId, String appId) {
        List<VersionCenterIndex> list =
                versionCenterRepository.findByVersionItem_ProjectIdAndVersionItem_AppId(projectId, appId);
        List<VersionItemVo> result = new ArrayList<>();
        for (VersionCenterIndex versionCenterIndex : list) {
            result.add(versionItemViewService.convertVersionItem(versionCenterIndex));
        }
        return result;
    }

    public Page<VersionItemVo> getVersionItemList(String projectId, String appId, Pageable pageable) {
        Page<VersionCenterIndex> page =
                versionCenterRepository.findByVersionItem_ProjectIdAndVersionItem_AppId(projectId, appId, pageable);
        List<VersionItemVo> vos = page.getContent().stream()
                .map(versionItemViewService::convertVersionItem)
                .collect(Collectors.toList());
        return new PageImpl<>(vos, pageable, page.getTotalElements());
    }

    public VersionItemVo getLastVersionItem(String projectId, String appId) {
        List<VersionCenterIndex> items =
                versionCenterRepository.findTop1ByVersionItem_ProjectIdAndVersionItem_AppIdOrderByCreateTimeDesc(projectId, appId);
        VersionCenterIndex item = (items != null && !items.isEmpty()) ? items.get(0) : null;
        return Optional.ofNullable(item).map(versionItemViewService::convertVersionItem).orElse(null);
    }

    public void deleteVersionItem(String id) {
        Optional<VersionCenterIndex> indexOpt = versionCenterRepository.findById(id);
        if (indexOpt.isPresent()) {
            VersionItem item = indexOpt.get().getVersionItem();
            if (item != null && StringUtils.hasText(item.getProgramFile())) {
                deleteCacheFile(item.getProgramFile(), "version");
            }
        }
        versionCenterRepository.deleteById(id);
    }

    public VersionItemVo getVersionByGitInfo(String appId, String versionNumber, String branch, String commitId) {
        List<VersionCenterIndex> results =
                versionCenterRepository.findTop1ByVersionItem_AppIdAndVersionItem_VersionNumberAndVersionItem_RepoBranchAndVersionItem_RepoCommitId(appId, versionNumber, branch, commitId);
        VersionCenterIndex c = (results != null && !results.isEmpty()) ? results.get(0) : null;
        return c == null ? null : versionItemViewService.convertVersionItem(c);
    }

    public void deleteCacheFile(String path) {
        deleteCacheFile(path, "cache");
    }

    private void deleteCacheFile(String path, String label) {
        if (!StringUtils.hasText(path)) {
            return;
        }
        try {
            File cacheRoot = new File(resourceService.getCacheRoot());
            File file = new File(cacheRoot, path);
            if (!file.exists()) {
                return;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.equals(cacheRoot) && parent.getParentFile().equals(cacheRoot)) {
                FileSystemUtils.deleteRecursively(parent);
                logger.info("Deleted {} directory: {}", label, parent.getAbsolutePath());
            } else if (file.delete()) {
                logger.info("Deleted {} file: {}", label, file.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Error deleting {} file: {}", label, path, e);
        }
    }
}
