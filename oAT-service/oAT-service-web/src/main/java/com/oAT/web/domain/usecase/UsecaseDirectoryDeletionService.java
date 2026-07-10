package com.oAT.web.domain.usecase;

import com.oAT.web.esDao.CaseCenterRepository;
import com.oAT.web.esDao.entity.CaseCenterIndex;
import com.oAT.web.esDao.entity.Usecase;
import com.oAT.web.service.entity.DirectoryDeleteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class UsecaseDirectoryDeletionService {

    @Autowired
    private CaseCenterRepository centerRepository;

    public Boolean delFolder(String directoryId, String parentId) {
        String[] childId = centerRepository.findById(directoryId).get().getDirectory().getChildId();
        Usecase usecase = centerRepository.findById(directoryId).get().getUsecase();
        if (childId.length > 0 || usecase != null) {
            return false;
        }

        unlinkDirectoryFromParent(parentId, directoryId);
        centerRepository.deleteById(directoryId);
        return true;
    }

    public int countUsecasesInDirectory(String projectId, String directoryId) {
        return collectDirectoryDeleteStats(projectId, directoryId).usecaseCount;
    }

    public int countDirectoryDescendants(String projectId, String directoryId) {
        DirectoryDeleteStats stats = collectDirectoryDeleteStats(projectId, directoryId);
        return Math.max(stats.directoryIds.size() - 1, 0);
    }

    public int deleteDirectoryWithUsecases(String projectId, String directoryId) {
        DirectoryDeleteStats stats = collectDirectoryDeleteStats(projectId, directoryId);
        for (String usecaseId : stats.usecaseIds) {
            centerRepository.deleteById(usecaseId);
        }
        for (int i = stats.directoryIds.size() - 1; i >= 0; i--) {
            String currentDirectoryId = stats.directoryIds.get(i);
            CaseCenterIndex currentDirectoryIndex = centerRepository.findById(currentDirectoryId).orElse(null);
            if (currentDirectoryIndex == null || currentDirectoryIndex.getDirectory() == null) {
                continue;
            }
            unlinkDirectoryFromParent(currentDirectoryIndex.getDirectory().getParentId(), currentDirectoryId);
            centerRepository.deleteById(currentDirectoryId);
        }
        return stats.usecaseCount;
    }

    public DirectoryDeleteResult previewDeleteDirectory(String projectId, String directoryId) {
        Assert.hasText(projectId, "param 'projectId' must be not null");
        Assert.hasText(directoryId, "param 'directoryId' must be not null");
        DirectoryDeleteResult result = new DirectoryDeleteResult();
        result.setDirectoryCount(countDirectoryDescendants(projectId, directoryId));
        result.setUsecaseCount(countUsecasesInDirectory(projectId, directoryId));
        result.setRequiresCascade(result.getDirectoryCount() > 0 || result.getUsecaseCount() > 0);
        result.setDeleted(false);
        if (result.isRequiresCascade()) {
            StringBuilder message = new StringBuilder("该目录删除前需要确认级联删除");
            if (result.getDirectoryCount() > 0 || result.getUsecaseCount() > 0) {
                message.append("：");
                if (result.getDirectoryCount() > 0) {
                    message.append(result.getDirectoryCount()).append("个子目录");
                }
                if (result.getUsecaseCount() > 0) {
                    if (result.getDirectoryCount() > 0) {
                        message.append("，");
                    }
                    message.append(result.getUsecaseCount()).append("个用例");
                }
                message.append(" 将一并删除");
            }
            result.setMessage(message.toString());
        } else {
            result.setMessage("目录删除预检成功");
        }
        return result;
    }

    public DirectoryDeleteResult deleteDirectory(String projectId, String directoryId, String parentId, boolean cascade) {
        DirectoryDeleteResult result = previewDeleteDirectory(projectId, directoryId);
        if (result.isRequiresCascade() && !cascade) {
            return result;
        }
        if (result.isRequiresCascade()) {
            int deletedUsecaseCount = deleteDirectoryWithUsecases(projectId, directoryId);
            result.setUsecaseCount(deletedUsecaseCount);
            result.setDeleted(true);
            StringBuilder message = new StringBuilder("目录删除成功");
            if (result.getDirectoryCount() > 0 || deletedUsecaseCount > 0) {
                message.append("，共删除");
                if (result.getDirectoryCount() > 0) {
                    message.append(result.getDirectoryCount()).append("个子目录");
                }
                if (deletedUsecaseCount > 0) {
                    if (result.getDirectoryCount() > 0) {
                        message.append("和");
                    }
                    message.append(deletedUsecaseCount).append("个用例");
                }
            }
            result.setMessage(message.toString());
            return result;
        }

        boolean deleted = delFolder(directoryId, parentId);
        result.setDeleted(deleted);
        result.setMessage(deleted ? "用例目录删除成功" : "用例目录不为空，删除失败");
        return result;
    }

    private DirectoryDeleteStats collectDirectoryDeleteStats(String projectId, String directoryId) {
        DirectoryDeleteStats stats = new DirectoryDeleteStats();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(directoryId);
        while (!stack.isEmpty()) {
            String currentDirectoryId = stack.pop();
            CaseCenterIndex directoryIndex = centerRepository.findById(currentDirectoryId).orElse(null);
            if (directoryIndex == null || directoryIndex.getDirectory() == null) {
                continue;
            }
            if (!projectId.equals(directoryIndex.getDirectory().getProjectId())) {
                continue;
            }
            if (!stats.visitedDirectoryIds.add(currentDirectoryId)) {
                continue;
            }
            stats.directoryIds.add(currentDirectoryId);

            List<CaseCenterIndex> usecases = centerRepository.findByUsecase_ProjectIdAndAndUsecase_Directory(projectId, currentDirectoryId, PageRequest.of(0, 1000));
            for (CaseCenterIndex usecaseIndex : usecases) {
                if (usecaseIndex == null || usecaseIndex.getUsecase() == null) {
                    continue;
                }
                if (stats.visitedUsecaseIds.add(usecaseIndex.getId())) {
                    stats.usecaseIds.add(usecaseIndex.getId());
                    stats.usecaseCount++;
                }
            }

            String[] childIds = directoryIndex.getDirectory().getChildId();
            if (childIds == null || childIds.length == 0) {
                continue;
            }
            for (int i = childIds.length - 1; i >= 0; i--) {
                String childId = childIds[i];
                if (StringUtils.hasText(childId)) {
                    stack.push(childId);
                }
            }
        }
        return stats;
    }

    private void unlinkDirectoryFromParent(String parentId, String directoryId) {
        if (!StringUtils.hasText(parentId) || "root".equals(parentId)) {
            return;
        }
        CaseCenterIndex parentIndex = centerRepository.findById(parentId).orElse(null);
        if (parentIndex == null || parentIndex.getDirectory() == null || parentIndex.getDirectory().getChildId() == null) {
            return;
        }
        List<String> parentChildIdList = new ArrayList<>(Arrays.asList(parentIndex.getDirectory().getChildId()));
        if (!parentChildIdList.remove(directoryId)) {
            return;
        }
        parentIndex.getDirectory().setChildId(parentChildIdList.toArray(new String[0]));
        centerRepository.save(parentIndex);
    }

    private static class DirectoryDeleteStats {
        private final List<String> directoryIds = new ArrayList<>();
        private final List<String> usecaseIds = new ArrayList<>();
        private final Set<String> visitedDirectoryIds = new HashSet<>();
        private final Set<String> visitedUsecaseIds = new HashSet<>();
        private int usecaseCount;
    }
}
