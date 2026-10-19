package com.algoeval.ai.nl2sql;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 白名单校验器（NL2SQL 的安全边界——真实系统面向 ClickHouse，demo 面向 H2，语义相同）。
 * <p>
 * 原则：LLM 生成的 SQL 不可信，必须过守卫——
 * <ul>
 *   <li>只允许单条 SELECT（禁 DML/DDL/调用/注释/多语句）</li>
 *   <li>表白名单：评价域只读表</li>
 *   <li>强制 LIMIT（默认 200，上限 500）——大查询兜底</li>
 * </ul>
 * 守卫拒绝的一切都被拒绝——fail closed，不是 fail open。
 */
@Component
public class SqlGuard {

    /** 只读表白名单 */
    private static final Set<String> TABLE_WHITELIST = Set.of(
            "indicator_result", "parent_order", "child_order",
            "algo_daily_summary", "realtime_metric", "route_decision",
            "t0_order_detail", "to_day_detail", "secu_symbol", "industry_baseline");

    /** 禁止关键词（DML/DDL/流程控制/注释/多语句） */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|create|truncate|merge|grant|revoke|call|exec|execute|into|set)\\b"
                    + "|--|/\\*|\\*/|;");

    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)");

    static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 200;

    /** 校验结果 */
    public record Verdict(boolean allowed, String sql, String rejection) {
    }

    /**
     * 校验并规范化 SQL：通过则返回带 LIMIT 的可执行语句，否则返回拒绝原因。
     */
    public Verdict check(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return new Verdict(false, null, "SQL 为空");
        }
        String sql = rawSql.trim();
        // 去掉行尾分号后不允许再出现分号（多语句切割）
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (!sql.toLowerCase(Locale.ROOT).startsWith("select")) {
            return new Verdict(false, null, "只允许 SELECT 查询");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            return new Verdict(false, null, "包含禁止的关键词或注释（只读白名单校验失败）");
        }
        Matcher m = TABLE_REF.matcher(sql);
        boolean foundTable = false;
        while (m.find()) {
            foundTable = true;
            String table = m.group(1).toLowerCase(Locale.ROOT);
            if (!TABLE_WHITELIST.contains(table)) {
                return new Verdict(false, null, "表不在白名单内: " + table);
            }
        }
        if (!foundTable) {
            return new Verdict(false, null, "未识别到白名单表引用");
        }
        return new Verdict(true, withLimit(sql), null);
    }

    /** 强制 LIMIT：没有则补默认值；有则压到上限 */
    private String withLimit(String sql) {
        Pattern limitPattern = Pattern.compile("(?i)\\blimit\\s+(\\d+)\\s*$");
        Matcher m = limitPattern.matcher(sql);
        if (m.find()) {
            int limit = Integer.parseInt(m.group(1));
            if (limit > MAX_LIMIT) {
                return m.replaceFirst("LIMIT " + MAX_LIMIT);
            }
            return sql;
        }
        return sql + " LIMIT " + DEFAULT_LIMIT;
    }

    /** 白名单表清单（LLM 提示词与前端展示用） */
    public List<String> whitelistedTables() {
        return List.copyOf(TABLE_WHITELIST);
    }
}
