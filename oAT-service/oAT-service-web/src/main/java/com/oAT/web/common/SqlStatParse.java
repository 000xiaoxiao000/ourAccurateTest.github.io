package com.oAT.web.common;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SqlStatParse {

    private List<SqlParseInfo> adds = new ArrayList<>();
    private List<SqlParseInfo> deletes = new ArrayList<>();
    private List<SqlParseInfo> selects = new ArrayList<>();
    private List<SqlParseInfo> updates = new ArrayList<>();
    private List<SqlParseInfo> drops = new ArrayList<>();
    private List<SqlParseInfo> creates = new ArrayList<>();

    public SqlStatParse() {
    }

    public SqlStatParse(String sql, String dbType) {
        addSql(sql, dbType);
    }


    public void addSql(String sql, String dbType) {
        parse(sql, dbType);
    }

    private void parse(String sql, String dbType) {
        DbType resolvedDbType = resolveDbType(dbType);
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, resolvedDbType);
        for (SQLStatement sqlStatement : stmtList) {
            SchemaStatVisitor statVisitor = SQLUtils.createSchemaStatVisitor(resolvedDbType);
            sqlStatement.accept(statVisitor);
            for (Map.Entry<TableStat.Name, TableStat> tables : statVisitor.getTables().entrySet()) {
                SqlParseInfo statData = new SqlParseInfo(tables.getKey().toString(),
                        tables.getValue().toString().toLowerCase());
                statData.setSql(sql);
                for (TableStat.Column column : statVisitor.getColumns()) {
                    if (column.getTable().equals(statData.getTableName())) {
                        statData.addColumns(column.getName());
                    }
                }
                if (statData.getModel().equalsIgnoreCase("insert")) {
                    adds.add(statData);
                } else if (statData.getModel().equalsIgnoreCase("update")) {
                    updates.add(statData);
                } else if (statData.getModel().equalsIgnoreCase("delete")) {
                    deletes.add(statData);
                } else if (statData.getModel().equalsIgnoreCase("select")) {
                    selects.add(statData);
                } else if (statData.getModel().equalsIgnoreCase("drop")) {
                    drops.add(statData);
                } else if (statData.getModel().equalsIgnoreCase("create")) {
                    creates.add(statData);
                }
            }

        }
    }


    private DbType resolveDbType(String dbType) {
        DbType resolvedDbType = DbType.of(dbType);
        return resolvedDbType == null ? DbType.postgresql : resolvedDbType;
    }

    public List<SqlParseInfo> getAdds() {
        return adds;
    }

    public List<SqlParseInfo> getDeletes() {
        return deletes;
    }

    public List<SqlParseInfo> getSelects() {
        return selects;
    }

    public List<SqlParseInfo> getUpdates() {
        return updates;
    }

    public List<SqlParseInfo> getDrops() {
        return drops;
    }

    public List<SqlParseInfo> getCreates() {
        return creates;
    }

    public ArrayList<SqlParseInfo> getAll() {
        ArrayList<SqlParseInfo> list = new ArrayList<>();
        list.addAll(adds);
        list.addAll(deletes);
        list.addAll(selects);
        list.addAll(updates);
        list.addAll(drops);
        list.addAll(creates);
        return list;
    }

}
