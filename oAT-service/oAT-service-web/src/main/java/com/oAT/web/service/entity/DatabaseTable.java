package com.oAT.web.service.entity;

public class DatabaseTable implements java.io.Serializable {
    public String database;
    public String dataBaseType;
    public String table;

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDataBaseType() {
        return dataBaseType;
    }

    public void setDataBaseType(String dataBaseType) {
        this.dataBaseType = dataBaseType;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String toName() {
        return database + "." + table;
    }
}
