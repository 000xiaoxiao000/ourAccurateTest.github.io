package com.oAT.web.esDao.entity;


import java.io.Serializable;

public class UsecaseSql implements Serializable {
    /*
    格式：${db_type} ${db_name}  ${sql}
     */
    private String[] contents;
    /*
    插入操作
     */
    private SqlAction[] inserts;
    /*
    修改操作
     */
    private SqlAction[] updates;
    /*
    删除插操
     */
    private SqlAction[] deletes;
    /*
    查询操作
     */
    private SqlAction[] selects;
    /*
    drop操作
     */
    private SqlAction[] drops;
    /*
    创建操作
     */
    private SqlAction[] creates;


    public String[] getContents() {
        return contents;
    }

    public void setContents(String[] contents) {
        this.contents = contents;
    }

    public SqlAction[] getInserts() {
        return inserts;
    }

    public void setInserts(SqlAction[] inserts) {
        this.inserts = inserts;
    }

    public SqlAction[] getUpdates() {
        return updates;
    }

    public void setUpdates(SqlAction[] updates) {
        this.updates = updates;
    }

    public SqlAction[] getDeletes() {
        return deletes;
    }

    public void setDeletes(SqlAction[] deletes) {
        this.deletes = deletes;
    }


    public SqlAction[] getDrops() {
        return drops;
    }

    public void setDrops(SqlAction[] drops) {
        this.drops = drops;
    }

    public SqlAction[] getCreates() {
        return creates;
    }

    public void setCreates(SqlAction[] creates) {
        this.creates = creates;
    }

    public SqlAction[] getSelects() {
        return selects;
    }

    public void setSelects(SqlAction[] selects) {
        this.selects = selects;
    }

    public static class SqlAction {
        private String name;       //格式：DataBase.table.cloumn
        private int index;
        private String sqlText;

        public SqlAction(String name, int index, String sqlText) {
            this.name = name;
            this.index = index;
            this.sqlText = sqlText;
        }

        public SqlAction() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public String getSqlText() {
            return sqlText;
        }

        public void setSqlText(String sqlText) {
            this.sqlText = sqlText;
        }

    }
}
