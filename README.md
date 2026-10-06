# LLM Gateway

企业接入多家大模型的统一入口层。对上提供 OpenAI 兼容协议，对下适配多家厂商，中间解决四件事：**成本、稳定性、多租户配额、可观测**。

一个不需要真实 API key、不需要 Docker、不需要 Redis 就能完整跑起来并复现全部数据的实现。

- Java 21（虚拟线程）+ Spring Boot 3.3
- 64 个单元测试；下面每一张表都能用仓库里的脚本重跑出来

---

## 请求生命周期

```
Client
  → API-Key 认证 / 租户解析            （明文 key 只在内存里留 SHA-256 摘要）
  → 请求归一化 → 计算缓存键             （递归排序对象键；stream/user 不进摘要）
  → 缓存查询                           （命中：直接回放 SSE，不打厂商）
  → 准入控制                           （并发租约 + rpm 令牌桶 + tpm 预扣估算值）
  → 路由打分                            （成本 / 实测延迟 / 窗口错误率 + 熔断过滤）
  → 上游流式调用，逐 chunk 转发          （TTFT、chunk 间隔、真实 usage 采样）
  → 结算                               （真实 token 与预扣差额回补 → 缓存条件写入）
  → 指标与结构化日志                     （trace-id 贯穿）
```

## 模块地图

| 包 | 职责 |
|---|---|
| `protocol` | OpenAI 兼容 DTO、规范化请求视图 |
| `upstream` | `UpstreamAdapter` SPI、JDK HttpClient 实现、连接复用、**流取消句柄** |
| `router` | 打分路由、滚动健康窗口、熔断器 |
| `ratelimit` | 令牌桶 SPI（内存 / Redis Lua）、并发租约、token 估算、配额编排 |
| `cache` | 缓存键规范化、Caffeine 存储、命中回放编解码 |
| `auth` | API-Key 摘要认证、租户目录 |
| `obs` | Micrometer 指标：TTFT、端到端/上游延迟、放弃流、降级、配额拒绝维度 |
| `mock` | 故障注入上游模拟器（延迟 / 错误率 / 沉默 / 活跃流计数） |

`mock` 用 JDK 内置 `com.sun.net.httpserver` 实现，不依赖框架——否则无法在不烧 token 的前提下重复压测和做混沌测试。

## 快速开始

```bash
# 需要 JDK 21。首次会生成 tools/cp.txt
bash tools/dev-up.sh          # 起 2 个 mock 上游（9090 快/贵，9091 慢/便宜）+ 网关（8080）

bash tools/test-cache.sh      # 缓存身份与命中
bash tools/test-quota.sh      # rpm / tpm / 并发上限
bash tools/test-routing.sh    # 打分、熔断打开与恢复
bash tools/test-cancellation.sh
bash tools/measure-detection.sh 2s   # 断连检测延迟

curl -s localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-alpha-dev' \
  -d '{"model":"gw-demo","stream":true,"messages":[{"role":"user","content":"hi"}]}'

bash tools/dev-down.sh
```

## 实测结果

> 上游耗时由 mock 人为注入（`chunk_delay_ms`），所以绝对值不代表真实厂商；重点是**同一负载下开/关某个机制的差值**。

### 客户端断连后，多久释放上游连接

场景：厂商发出 1 个 token 后沉默 9 秒；客户端在第 1 秒离开。`tools/measure-detection.sh`。

| 配置 | 客户端离开后上游释放耗时 |
|---|---|
| 心跳关闭 | 8647 ms |
| 心跳 2 s | 3396 ms |
| 心跳 500 ms | 1396 ms |

两个非显然的结论：

1. **中断线程不会关闭上游连接。** 最初版本只 `Future.cancel(true)`，实测三种心跳配置全部约 8.6 s——虚拟线程被中断不等于 HttpClient 的响应体流被关闭，厂商连接一直活着、继续生成、继续计费。必须显式 `InputStream.close()`（`StreamAbortHandle`）。
2. **检测延迟约等于 2 个心跳周期**，不是 1 个：断连后的第一次写会进内核缓冲并成功，RST 返回后下一次写才失败。心跳的作用是**保证存在一次写**——否则发现时间由厂商下次吐字的节奏决定，无上界。

### 缓存

| 验证 | 结果 |
|---|---|
| 同一请求第二次 | 823 ms → **6 ms**，`X-Upstream: cache` 证明未打厂商 |
| `stream:true` 命中阻塞请求写入的条目 | ✅ 传输方式不进摘要 |
| `temperature=0.9` | 永不缓存（采样结果缓存等于把一次掷骰子固化整个 TTL） |
| 租户隔离 | beta 读不到 alpha 的条目；关掉开关后共享 |
| `length` / `content_filter` 结束的回答 | 不写入（否则把截断答案冻结进缓存） |

字段过滤是**黑名单**而非白名单：未知厂商参数默认参与摘要。白名单的风险是"新参数默认不进摘要 → 缓存串味"，而黑名单最坏只是多一次未命中。

### 配额（tenant rpm=30 / tpm / max-concurrency=4）

| 场景 | 实测 |
|---|---|
| 60 连发，rpm=30 | 放行 **31**，第 31 个开始 429，带 `Retry-After` |
| 20 路并发，上限 4 | **4 放行 / 16 拒绝**，且拒绝方不残留租约（下一批正常放行） |
| 流式预扣回补 | 预扣 **515**，按真实 usage 回补 **505**，实扣 **10** |
| 拒绝归因 | `dimension="rpm"` / `"concurrency"` 分维度计数 |

token 配额按 **token 维度**而非请求维度，这跟传统限流不是一回事：请求进来时还不知道要花多少 token，所以做"估算预扣 + 事后回补"。估算**故意不做真实 BPE 编码**——在网关热路径上逐请求跑 tokenizer 是奢侈品，代价是 P99 前缀延迟；用"字符数 + CJK 逐字"保守估算，误差由回补修正，超发部分记为 overage 而不是追认额度。

### 熔断与打分

故障注入后（primary 100% 返回 503），熔断打开期间的 25 个请求：

```
失败跳数：1          （纯"重试下一个"方案：25）
状态迁移：CLOSED→OPEN ×1，OPEN→HALF_OPEN ×4，
          HALF_OPEN→OPEN ×3，HALF_OPEN→CLOSED ×1
探测结果：failed ×3, ok ×2
```

三处是**测出来才发现问题**的设计：

1. **纯错误率熔断在低流量下是死的。** 打分把故障厂商的流量分走后，它的健康窗口永远攒不满 `minVolume`，`circuit_transition` 计数一度为 0。补了连续失败判据（3 次即跳）才有结论。
2. **错误率不能按候选最大值归一化。** 两个厂商时任何非零错误都被放大成 1.0，等于把"一次被置信度收缩过的失败"和"彻底坏了"划等号。错误率改用绝对值（它本来就是 0–1）。
3. **无样本的厂商必须先被测量。** 给未知厂商"按已知均值假设 + 分数折扣"试过，结果该厂商 `window_requests=0`，彻底饿死、也就永远测不出真实延迟。改成硬规则：**未观测候选排在一切有证据的候选之前**。代价是每个厂商都要付一点预热流量。

熔断全部打开时**强制放行**而不是拒绝：部分故障不该变成全站不可用，同时 `gateway_route_forced` 让它可见。

### 并发模型

选 Spring MVC + 虚拟线程，而不是 WebFlux。上游是长耗时纯 IO，虚拟线程把"一请求一线程"的成本降到接近响应式，同时保留同步代码可读性和取消语义。

一个具体后果：转发工作线程和心跳线程会并发写同一个 SSE 响应，需要互斥——这里用 `ReentrantLock` 而不是 `synchronized`，因为 JDK 21 上阻塞的 `synchronized` 会 **pin 住 carrier thread**，正好抵消虚拟线程的收益（JEP 491 才解决）。

## 测试

```bash
mvn test    # 64 个
```

覆盖：令牌桶算术（含内存实现与 Lua 实现的共同规范）、并发取令牌不超发、租约过期回收、估算器向上取整、配额预扣/回补/超发不追认、缓存键规范化与租户隔离、缓存编解码的截断拒绝、健康窗口滑出与陈旧轮次、熔断四态迁移、打分排序与权重翻转。

## 已知边界（主动交代）

- **Redis / Lua 分布式配额未在本环境运行验证**：没有本地 Redis。桶的算术由 `TokenBucketMath` 抽出并被单测证明，Lua 是它的转录，条件装配 + 内存实现兜底。`gateway.quota.distributed=true` 才启用。
- **计费流水尚未落库**：目前用量在指标里（按租户/模型累计 token、缓存节省、配额预扣与回补），还没有 `usage_ledger` 表和异步批量写 + 对账。
- **压测数据待补**：并发流式连接拐点、"网关端到端 P99 − 上游 P99 = 网关自身开销"的正式测量还没跑。
- **语义缓存在规划中**：只做精确匹配。语义相似检索的误命中代价是"答错"，需要离线评测集校准阈值后才敢上。
- 演示配置里的 `sk-*-dev` 是本机假 key，只对接 mock 上游，不含任何真实凭据。
