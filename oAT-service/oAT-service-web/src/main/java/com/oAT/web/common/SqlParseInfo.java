package com.oAT.web.common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SqlParseInfo implements Serializable {
    private String tableName;
    // select, insert, update, delete;
    private String model;
    private List<String> columns = new ArrayList<>();
    private String sql;

    public SqlParseInfo(String tableName, String model) {
        this.tableName = tableName;
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void addColumns(String column) {
        columns.add(column);
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}
        if (o == null || getClass() != o.getClass()) {return false;}

        SqlParseInfo that = (SqlParseInfo) o;

        if (tableName != null ? !tableName.equals(that.tableName) : that.tableName != null) {return false;}
        if (model != null ? !model.equals(that.model) : that.model != null) {return false;}
        if (columns != null ? !columns.equals(that.columns) : that.columns != null) {return false;}
        return sql != null ? sql.equals(that.sql) : that.sql == null;
    }

    @Override
    public int hashCode() {
        int result = tableName != null ? tableName.hashCode() : 0;
        result = 31 * result + (model != null ? model.hashCode() : 0);
        result = 31 * result + (columns != null ? columns.hashCode() : 0);
        result = 31 * result + (sql != null ? sql.hashCode() : 0);
        return result;
    }
}
