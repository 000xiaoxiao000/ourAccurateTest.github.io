package com.oAT.web.service.impl;

import com.oAT.web.esDao.SystemRepository;
import com.oAT.web.esDao.entity.SystemLog;
import com.oAT.web.esDao.entity.SystemIndex;
import com.oAT.web.service.SystemLogService;
import com.oAT.web.service.entity.SystemLogVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

@Service
public class SystemLogServiceImpl implements SystemLogService{

    @Autowired
    SystemRepository systemRepository;

    @Override
    public void addLog(SystemLog log) {
        Assert.notNull(log, "参数log 不能为空");
//        Assert.notNull(log.getProjectId(), "参数log.ProjectId 不能为空");
        Assert.notNull(log.getTitle(), "参数log.Title 不能为空");
        Assert.notNull(log.getUserId(), "参数log.UserId 不能为空");
        // 超出部分进行截取...
        if (log.getMessage()!=null) {
            log.setMessage(log.getMessage().substring(0, Math.min(log.getMessage().length(), 256)));
        }
        SystemIndex index = new SystemIndex(log);
        systemRepository.save(index);
    }

    @Override
    public List<SystemLogVo> getSystemLog(String projectId, int page, int size) {
        List<SystemLogVo> result = new ArrayList<>(size);
        Pageable p = PageRequest.of(page, size, Sort.Direction.DESC, "createTime");
        List<SystemIndex> list = systemRepository.findBySystemLog_ProjectId(projectId, p);
        for (SystemIndex index : list) {
            result.add(convert(index));
        }
        return result;
    }

    private SystemLogVo convert(SystemIndex index) {
        SystemLogVo logVo = new SystemLogVo();
        logVo.setCreateTime(index.getCreateTime());
        logVo.setId(index.getId());
        BeanUtils.copyProperties(index.getSystemLog(), logVo);
        return logVo;
    }

}
