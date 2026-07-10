package com.oAT.web.esDao.entity;


import java.io.Serializable;

public class CKSql implements Serializable{

    /**
     * 数据库
     */
    private String database;
    /**
     * 数据库类型
     */
    private String databaseType;
    /**
     * 语句内容
     */
    private String content;
    /**
     * 操作集
     */
    private Sql.Action actions[];
    /**
     * 执行次数
     */
    private Integer count;


    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Sql.Action[] getActions() {
        return actions;
    }

    public void setActions(Sql.Action[] actions) {
        this.actions = actions;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(String databaseType) {
        this.databaseType = databaseType;
    }

    public static class Action implements Serializable {
        private String type;
        private String table;

        public Action() {
        }

        public Action(String type, String table) {
            this.type = type;
            this.table = table;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }
    }
}
