package com.oAT.web.service.entity;

import java.io.Serializable;

// TODO 表搜索的临时解决方案，后续接入更稳定的关系数据。
public class TableToUsecase implements Serializable {
    // 数据库名称
    private String database;
    // 表结构
    private String table;
    // 表操作
    private String action[];
    private UsecaseVo usecaseVo;

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String[] getAction() {
        return action;
    }

    public void setAction(String[] action) {
        this.action = action;
    }

    public UsecaseVo getUsecaseVo() {
        return usecaseVo;
    }

    public void setUsecaseVo(UsecaseVo usecaseVo) {
        this.usecaseVo = usecaseVo;
    }
}
