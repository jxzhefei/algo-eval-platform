package com.algoeval.source.sdk;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Collections;

/**
 * 数据库方言：默认轨道 H2（MySQL 兼容模式，upsert 用 MERGE INTO ... KEY），
 * full profile MySQL（INSERT ... ON DUPLICATE KEY UPDATE）。同一套 DDL 双轨运行。
 */
public class SqlDialect {

    private final boolean mysql;

    public SqlDialect(boolean mysql) {
        this.mysql = mysql;
    }

    public static SqlDialect detect(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            return new SqlDialect(conn.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql"));
        } catch (Exception e) {
            throw new IllegalStateException("数据库类型探测失败", e);
        }
    }

    public boolean isMysql() {
        return mysql;
    }

    /**
     * 生成幂等 upsert SQL。
     *
     * @param table   表名
     * @param columns 全部列
     * @param pkCount 主键列数（columns 前 pkCount 列为主键）
     */
    public String upsert(String table, String[] columns, int pkCount) {
        String cols = String.join(", ", columns);
        String placeholders = String.join(", ", Collections.nCopies(columns.length, "?"));
        if (mysql) {
            StringBuilder update = new StringBuilder();
            for (int i = pkCount; i < columns.length; i++) {
                if (update.length() > 0) {
                    update.append(", ");
                }
                update.append(columns[i]).append("=VALUES(").append(columns[i]).append(")");
            }
            return "INSERT INTO " + table + " (" + cols + ") VALUES (" + placeholders
                    + ") ON DUPLICATE KEY UPDATE " + update;
        }
        // H2：MERGE INTO t (cols) KEY(pk) VALUES (...)
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < pkCount; i++) {
            if (i > 0) {
                keys.append(", ");
            }
            keys.append(columns[i]);
        }
        return "MERGE INTO " + table + " (" + cols + ") KEY(" + keys + ") VALUES (" + placeholders + ")";
    }

    /** 普通 INSERT（配合唯一索引捕获冲突实现幂等初始化） */
    public String insert(String table, String[] columns) {
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", Collections.nCopies(columns.length, "?")) + ")";
    }
}
