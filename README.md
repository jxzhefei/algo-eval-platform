# 智能算法评价平台（algo-eval-platform）

> 采集全量行情与算法交易数据，对 TWAP / VWAP / T0 算法的执行质量做多维量化评价，
> 并把评价数据反哺算法选择（智能路由）——从"报表工具"演进为"交易基础设施"。
>
> **券商私有化部署形态**：平台卖给券商客户（数据完全隔离），同一套代码按客户规模裁剪部署。
> 设计基线见《智能算法评价平台-详细设计文档.md》。

---

## 1. 架构（真实中间件，全链路）

```
行情前置(JeroMQ PUB, Protobuf QuoteList)      模拟交易终端(TWAP/VWAP/T0 拆单)
        │ SUB 订阅                                      │ REST 上报
        ▼                                              ▼
┌─────────────────────┐                     ┌──────────────────────┐
│  collector-hq        │                     │  trade-ingest 接入层   │
│  ·Disruptor 管道     │                     │  ·母单/子单/回报接收    │
│  ·选主门控(Redisson) │                     │  ·第三方算法商归一化    │
│  ·观察者分发          │                     └──────────┬───────────┘
└─────────┬───────────┘                                │
          ▼                                            ▼
┌─────────────────────────────────────────────────────────────┐
│        Kafka（行情 Protobuf 批量 / 交易双 topic，削峰）           │
└──────────────┬──────────────────────────────┬────────────────┘
               ▼                              ▼
     ┌──────────────────┐          ┌──────────────────────┐
     │  data-storer      │          │ stream-calculate      │
     │  ·攒批消费(64KB/500ms)│       │ ·滑动窗口实时绩效      │
     │  ·ShardingSphere  │          │ ·WebSocket 推送        │
     │   按交易日分表      │          └──────────┬───────────┘
     └───────┬──────────┘                      │
             ▼                                 ▼
   ┌──────────────────┐             ┌────────────────────┐
   │ MySQL 8(分表)     │             │ Redis(实时指标/缓存)  │
   │ Seata AT 批次事务 │             └────────────────────┘
   └───────┬──────────┘
           │ 日定时同步(1万条/批) + 对账
           ▼
   ┌──────────────────┐
   │ ClickHouse        │  行情宽表 + 指标宽表（ReplacingMergeTree）
   └───────┬──────────┘
           │ 盘后触发
           ▼
   ┌────────────────────────────────────────────┐
   │ offline-calculate（无中心多节点抢占，§13）      │
   │ 32 分片 × N 节点 → 18 指标(7 基准×双口径)       │
   │ → Redisson 锁分层汇总 → 五维评分+行业均值       │
   │ → T0 明细/日汇总（t0_order_detail 等）         │
   └───────┬────────────────────────────────────┘
           ▼
   statistic / portrait（总览·排名·画像·相关性·行业均值·T0 概览）
   recommender（能力矩阵+智能路由+闭环回流） ai-insight（AI 解读/NL2SQL/离群检测）
           ▼
   前端（算法/T0 双轨菜单树：总览/算法评价/交易分析/智能推荐/日内监控/AI 分析）
```

注册与配置中心：**Nacos**（演进决策：etcd 退役，选主收敛 Redisson——组件净数量不变，§13.6）。

## 2. 快速开始（真实中间件轨道）

环境要求：JDK 21、Maven 3.9+、Docker Desktop。

```bash
# 1) 拉起全量中间件：MySQL 8 + Kafka(KRaft) + Redis + Nacos + ClickHouse + Seata TC
docker compose up -d

# 2) 构建并启动（模拟交易日约 4 分钟演完：采集→入库→实时→盘后计算→评分画像）
mvn clean spring-boot:run -pl platform-app

# 3) 打开 http://localhost:8080
```

中间件端点：

| 组件 | 端点 | 控制台 |
|------|------|--------|
| MySQL | localhost:3306（algodb/algoeval） | DBeaver |
| Kafka | localhost:9092（6 分区） | - |
| Redis | localhost:6379 | redis-cli |
| Nacos | localhost:8848 | http://localhost:8848/nacos |
| ClickHouse | localhost:8123(HTTP)/9008(native，对齐真实系统端口) | DBeaver |
| Seata TC | localhost:8091 | http://localhost:7091 |

> 镜像拉取网络受限时，可经镜像源拉取后 retag：`docker pull docker.m.daocloud.io/<image>` 或
> `docker pull docker.1ms.run/<image>`，再 `docker tag` 回标准名。

## 3. 一个模拟交易日的时间线

| 时间 | 事件 |
|------|------|
| T+0s | 建表（ShardingSphere 窗口预建 + 单表）→ 证券主档就绪 → 行情前置 PUB 绑定 → collector-hq SUB 订阅（Disruptor→Kafka） |
| T+0~4min | 模拟交易终端生成母单/子单，经 trade-ingest REST 上报 → Kafka → 攒批入库（按交易日分表）；日内监控页实时滚动 |
| T+4min+ | 收盘触发离线计算：3 节点 × 32 分片无中心抢占 → 18 指标 → Redisson 锁汇总 → 五维评分 + 行业均值 + T0 日汇总 |
| T+完成 | MySQL→ClickHouse 日同步 + 对账；总览/画像/相关性/行业均值/T0 概览/智能推荐/AI 分析全部可查 |

回放加速可调：`algo.sim.millis-per-minute`（默认 1000 = 1 秒真实时间播 1 市场分钟；设 60000 即实时回放）。

## 4. 模块清单

| 模块 | 职责 | 关键设计 |
|------|------|---------|
| `source-sdk` | 行情源插件契约（SPI）+ Protobuf QuoteList/QuoteItem 消息契约 | .proto 代码生成，数值字符串保精度 |
| `mock-source` | 模拟行情前置（GBM + U 型成交量，JeroMQ PUB 发布）+ 曲线查询 | 与生产共用同一传输契约 |
| `hq-source-jeromq` | JeroMQ 行情源插件（对应真实 collector-hq-source-jeromq） | SUB 订阅 → Disruptor |
| `collector-hq` | 行情采集 | Disruptor RingBuffer（零 GC）+ 观察者分发 + **Redisson 选主门控**（§13.6） |
| `trade-ingest` | 交易接入层（对应 atx-restful-acco / atx-third-guolian） | REST 接收 + 归一化 + 发布 Kafka |
| `collector-trade` | 模拟交易终端（TWAP/VWAP/T0 双腿回转拆单） | 经 REST 上报——接入边界真实生效 |
| `infra-core` | 基础设施 | ShardingSphere DateShardingAlgorithm 按交易日分表、Redisson 装配、Caffeine+Redis 二级缓存、ClickHouse 执行器、选主契约 |
| `data-storer` | 存储服务 | 真实攒批参数（64KB/500ms/手动提交/ErrorHandlingDeserializer/RoundRobin）+ MySQL→CK 日同步与对账 |
| `offline-calculate` | 离线并行计算 | 无中心抢占调度（任务表乐观锁/心跳/回收/熔断）、18 指标策略工厂、Seata AT 批次事务、五维评分+行业均值、T0 三表 |
| `stream-calculate` | 实时计算 | 5 分钟滑动窗口 + WebSocket（不上 Flink——私有化运维红线） |
| `statistic` | 统计 API | 总览双卡片/三维度排名/算法与算法商概览/评分+行业均值/对比/相关性四宫格/母单指标/T0 概览 |
| `portrait` | 画像 API | 市占率真实分桶 (0,0.1]~(100,∞)/交易时长分桶/自动综述 |
| `recommender` | 智能路由（V3 决策闭环） | 能力矩阵 + 贝叶斯收缩 + 可解释推荐 + 闭环回流 |
| `ai-insight` | AI 原生化（V3） | AI 解读 / NL2SQL 白名单守卫 fail-closed / MAD 离群检测；LLM 可插拔（私有化 Qwen/DeepSeek） |
| `platform-app` | all-in-one 启动器 | 组合全部模块 + 托管前端 |

## 5. 多节点离线计算（无中心抢占，§13）

```bash
# platform-app 之外，独立起 N 个计算实例（连同一 MySQL/Redis，真实多进程抢占）
mvn clean package -pl offline-calculate -DskipTests
for i in 1 2 3; do
  java -jar offline-calculate/target/offline-calculate-1.0.0-exec.jar \
       --spring.datasource.url="jdbc:mysql://localhost:3306/algodb?..." \
       --algo.redis.enabled=true &
done
```

节点完全对等：MySQL 任务表乐观锁抢占 → 心跳续约 → 超时回收重算 → 毒丸熔断；
汇总由 Redisson 锁串行化（`lock:summary:{algo}:{date}`）；批次提交走 Seata AT 全局事务。

## 6. 验证（离线计算三件套）

```bash
mvn test -pl offline-calculate
```

| 测试 | 证明什么 |
|------|---------|
| CorrectnessVerifierTest | 3 节点并发结果与单机基准**逐行一致**（不重/不漏/不脏，18 指标全列比对） |
| BenchmarkRunnerTest | 加速比 ≈ 节点数（work-stealing 负载均衡） |
| FaultInjectionTest | 运行中杀节点 → 心跳超时回收 → 其他节点重算 → 结果仍正确 |

幂等验证：同一交易日重复触发离线计算，结果表行数不变、汇总值一致。

## 7. 智能推荐与 AI 分析

- **智能推荐**：选标的/输金额/选方向 → 推荐算法 + 期望绩效 + 置信度 + 逐条可解释理由（每个数字可回溯历史评价样本）；「刷新校准报告」看闭环回流（推荐 vs 实际绩效命中率）。
- **AI 分析**：AI 解读报告（归因分解 + 受限生成）/ 对话查数（NL→SQL→**白名单守卫 fail-closed**→图表）/ MAD 稳健 Z 离群检测。
- 私有化 LLM（可选）：

```yaml
algo:
  ai:
    llm:
      base-url: http://内网IP:8000/v1   # OpenAI 兼容（vLLM/Ollama 部署的 Qwen/DeepSeek）
      model: qwen2.5-7b-instruct
```

未配置时全链路降级为本地受限生成——功能等价、行为可审计（券商内网合规边界）。

## 8. 关键配置

| 配置 | 默认 | 说明 |
|------|------|------|
| `algo.sim.orders` | 3000 | 模拟母单量（演示规模；真实头部客户日 30 万母单/90 万子单） |
| `algo.sim.millis-per-minute` | 1000 | 回放加速（毫秒/市场分钟） |
| `algo.sim.thirdparty.enabled` | false | 第三方算法商通道（异构报文归一化演示） |
| `algo.sharding.forward-days` | 30 | 分表预建窗口 |
| `algo.ck.enabled` | true | ClickHouse 同步/对账 |
| `algo.cache.l1-ttl-seconds` | 60 | Caffeine L1 TTL |
| `seata.enabled` | true | AT 批次事务（TC 在 compose 中） |
| `spring.cloud.nacos.*` | localhost:8848 | 注册 + 配置中心 |

## 9. 设计文档

| 文档 | 内容 |
|------|------|
| `智能算法评价平台-详细设计文档.md` | **权威设计**：业务背景（券商私有化、头部客户日 30 万母单/90 万子单）+ 现状架构 + 演进设计（多节点并行/Nacos/ClickHouse/Seata）+ 与真实系统的差异对照（附录 A）+ 资料证据索引（附录 B/B2） |

## 10. 技术栈

Java 21 · Spring Boot 3.3 · Disruptor 4 · Kafka 3.7(KRaft) · MySQL 8 + ShardingSphere 5.5 ·
ClickHouse 24.8 · Redis 7 + Redisson 3.32 · Seata AT 2.2 · Nacos 2.3 · JeroMQ + Protobuf ·
Caffeine · ECharts · Maven 多模块（16 模块）

> 开发提示：VS Code 的 Java 语言服务会后台编译并覆写 target/classes（ECJ 错误桩），
> 构建请始终使用 `mvn clean ...`，或在 VS Code 中关闭 Java 自动构建。
