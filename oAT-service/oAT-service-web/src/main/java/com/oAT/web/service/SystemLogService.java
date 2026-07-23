package com.oAT.web.service;

import com.oAT.web.persistence.entity.SystemLog;
import com.oAT.web.service.entity.SystemLogVo;

import java.util.List;

public interface SystemLogService {

    void addLog(SystemLog log);

    List<SystemLogVo> getSystemLog(String projectId, int page, int size);

    enum Action {
        deleteApp("删除应用"),
        editApp("更新应用"),
        addApp("添加应用"),
        addProject("创建项目"),
        deleteProject("删除项目"),
        deleteReport("删除报告"),
        generateReport("生成报告");

        public String name;

        Action(String name) {
            this.name = name;
        }
    }

}
