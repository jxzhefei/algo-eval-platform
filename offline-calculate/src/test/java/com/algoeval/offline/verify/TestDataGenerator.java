package com.algoeval.offline.verify;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 验证数据集生成器：固定种子、可控规模，直接写库（绕过采集链路，专注计算引擎验证）。
 * 生成 50 标的 × 240 分钟行情 + N 母单 + 每母单 5~30 子单。
 * 供应商口径与生产一致：我方自研算法（算法商=KAFANG），无虚构第三方。
 */
public class TestDataGenerator {

    public static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 16);
    public static final int SYMBOL_COUNT = 50;
    public static final int MARKET_MINUTES = 240;
    private static final String VENDOR = "KAFANG";
    private static final String[] ALGOS = {"kf_twap_plus", "kf_vwap_plus", "kf_t0"};
    private static final String[] ALGO_TYPES = {"TWAP", "VWAP", "T0"};

    private final JdbcTemplate jdbcTemplate;
    private final long seed;

    public TestDataGenerator(JdbcTemplate jdbcTemplate, long seed) {
        this.jdbcTemplate = jdbcTemplate;
        this.seed = seed;
    }

    /** 生成并落库完整数据集（行情 + 母单 + 子单）。先清场：测试类共享同一内存库，避免数据集互相污染 */
    public void generate(int parentCount) {
        resetComputeArtifacts();
        jdbcTemplate.update("DELETE FROM child_order");
        jdbcTemplate.update("DELETE FROM parent_order");
        jdbcTemplate.update("DELETE FROM market_tick");
        Random random = new Random(seed);
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < SYMBOL_COUNT; i++) {
            symbols.add(String.format("%06d.%s", 600000 + i, i % 2 == 0 ? "SH" : "SZ"));
        }
        generateMarket(symbols, new Random(seed + 1));
        generateOrders(parentCount, symbols, new Random(seed + 2));
    }

    private void generateMarket(List<String> symbols, Random random) {
        String sql = "MERGE INTO market_tick (symbol, trade_date, tick_time, price, volume, turnover) "
                + "KEY(symbol, tick_time) VALUES (?,?,?,?,?,?)";
        Date date = Date.valueOf(TRADE_DATE);
        List<Object[]> batch = new ArrayList<>(SYMBOL_COUNT * MARKET_MINUTES);
        for (String symbol : symbols) {
            double price = 5 + random.nextDouble() * 95;
            double drift = (random.nextDouble() - 0.5) * 0.03 / MARKET_MINUTES;
            double minuteVol = 0.02 / Math.sqrt(MARKET_MINUTES);
            double baseVol = 20_000 + random.nextDouble() * 1_980_000;
            for (int m = 0; m < MARKET_MINUTES; m++) {
                price = Math.max(0.5, price * Math.exp(drift + random.nextGaussian() * minuteVol));
                double u = 1.0 + 2.0 * Math.pow(2.0 * m / (MARKET_MINUTES - 1) - 1, 2);
                long volume = Math.max(1000, Math.round(baseVol * u * (0.7 + random.nextDouble() * 0.6)));
                BigDecimal px = BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP);
                batch.add(new Object[]{symbol, date, Timestamp.valueOf(marketTime(m)), px, volume,
                        px.multiply(BigDecimal.valueOf(volume)).setScale(2, RoundingMode.HALF_UP)});
            }
        }
        jdbcTemplate.batchUpdate(sql, batch);
    }

    private void generateOrders(int parentCount, List<String> symbols, Random random) {
        List<Object[]> parents = new ArrayList<>(parentCount);
        List<Object[]> children = new ArrayList<>();
        long childId = 1;
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (long orderId = 1; orderId <= parentCount; orderId++) {
            int algoIdx = (int) (orderId % 10) < 4 ? 0 : ((int) (orderId % 10) < 8 ? 1 : 2);
            String algo = ALGOS[algoIdx];
            String algoType = ALGO_TYPES[algoIdx];
            boolean t0 = algoIdx == 2;
            String symbol = symbols.get(random.nextInt(symbols.size()));
            BigDecimal taskAmount = BigDecimal.valueOf(500_000 + random.nextDouble() * 19_500_000)
                    .setScale(2, RoundingMode.HALF_UP);
            int arrivalMinute = random.nextInt(MARKET_MINUTES - 65);
            int endMinute = Math.min(MARKET_MINUTES - 1, arrivalMinute + 60);
            // arrival 价需要行情：重新走一遍该标的价格序列代价高，直接用独立随机价（指标自洽性由子单价格基准保证）
            BigDecimal arrivalPrice = BigDecimal.valueOf(5 + random.nextDouble() * 95)
                    .setScale(4, RoundingMode.HALF_UP);
            String side = random.nextDouble() < 0.7 ? "BUY" : "SELL";
            double cancelRatio = t0 ? 0.10 : (algoIdx == 0 ? 0.15 : 0.08);

            int legCount = 5 + random.nextInt(26);
            long taskQty = Math.max(1000,
                    Math.round(taskAmount.doubleValue() / arrivalPrice.doubleValue() / 100) * 100);
            if (t0) {
                taskQty = taskQty * 2; // T0 任务口径 = 双腿开平名义
                taskAmount = BigDecimal.valueOf(taskQty).multiply(arrivalPrice)
                        .setScale(2, RoundingMode.HALF_UP);
            }
            BigDecimal filled = BigDecimal.ZERO;
            long filledQty = 0;
            List<Object[]> orderChildren = new ArrayList<>(legCount);
            for (int c = 0; c < legCount; c++) {
                int execMinute = Math.min(MARKET_MINUTES - 1, arrivalMinute + 1 + c * 2);
                long qty = Math.max(1000,
                        Math.round(taskQty / (double) legCount / 100) * 100);
                // T0 双腿：奇数腿买入、偶数腿卖出（回转）；普通算法同向
                String childSide = t0 ? (c % 2 == 0 ? "BUY" : "SELL") : side;
                boolean cancelled = random.nextDouble() < cancelRatio;
                boolean rejected = !cancelled && random.nextDouble() < 0.01;
                long filledQ = (cancelled || rejected) ? 0 : qty;
                BigDecimal fillPrice = null;
                if (filledQ > 0) {
                    // 围绕 arrival 价 ±0.5% 的成交偏移
                    fillPrice = arrivalPrice.multiply(
                                    BigDecimal.valueOf(1 + (random.nextDouble() - 0.5) * 0.01))
                            .setScale(4, RoundingMode.HALF_UP);
                    filled = filled.add(fillPrice.multiply(BigDecimal.valueOf(filledQ)));
                    filledQty += filledQ;
                }
                orderChildren.add(new Object[]{childId++, orderId, Date.valueOf(TRADE_DATE), childSide,
                        Timestamp.valueOf(marketTime(execMinute)), qty, arrivalPrice, filledQ, fillPrice,
                        cancelled ? 1 : 0, rejected ? 1 : 0});
            }
            BigDecimal avgFill = filledQty == 0 ? null
                    : filled.divide(BigDecimal.valueOf(filledQty), 4, RoundingMode.HALF_UP);
            int clientIdx = 1 + (int) ((orderId * 7) % 10);
            parents.add(new Object[]{orderId, Date.valueOf(TRADE_DATE), algo, algoType, VENDOR,
                    String.format("机构客户%02d", clientIdx), "2023031410" + String.format("%02d", clientIdx),
                    symbol, side, taskQty, taskAmount, filledQty, filled.setScale(2, RoundingMode.HALF_UP),
                    avgFill, arrivalPrice, Timestamp.valueOf(marketTime(arrivalMinute)),
                    Timestamp.valueOf(marketTime(endMinute)), "FINISHED", now});
            children.addAll(orderChildren);
        }
        jdbcTemplate.batchUpdate(
                "MERGE INTO parent_order (order_id, trade_date, algo_instance, algo_type, algo_vendor, "
                        + "client_name, account_id, symbol, side, task_qty, task_amount, filled_qty, "
                        + "filled_amount, avg_fill_price, arrival_price, start_time, end_time, status, "
                        + "update_time) KEY(order_id, trade_date) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                parents);
        jdbcTemplate.batchUpdate(
                "MERGE INTO child_order (child_id, order_id, trade_date, side, exec_time, qty, price, "
                        + "filled_qty, avg_fill_price, is_cancelled, is_rejected) "
                        + "KEY(child_id, trade_date) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                children);
    }

    private static LocalDateTime marketTime(int minute) {
        if (minute < 120) {
            return LocalDateTime.of(TRADE_DATE, LocalTime.of(9, 30)).plusMinutes(minute);
        }
        return LocalDateTime.of(TRADE_DATE, LocalTime.of(13, 0)).plusMinutes(minute - 120);
    }

    /** 清空计算产物（保留源数据），并复位任务表 */
    public void resetComputeArtifacts() {
        jdbcTemplate.update("DELETE FROM indicator_result WHERE trade_date=?", Date.valueOf(TRADE_DATE));
        jdbcTemplate.update("DELETE FROM algo_daily_summary WHERE trade_date=?", Date.valueOf(TRADE_DATE));
        jdbcTemplate.update("DELETE FROM compute_task WHERE trade_date=?", Date.valueOf(TRADE_DATE));
        jdbcTemplate.update("DELETE FROM t0_order_detail WHERE trade_date=?", Date.valueOf(TRADE_DATE));
        jdbcTemplate.update("DELETE FROM to_day_detail WHERE trade_date=?", Date.valueOf(TRADE_DATE));
        jdbcTemplate.update("DELETE FROM industry_baseline WHERE trade_date=?", Date.valueOf(TRADE_DATE));
    }
}
