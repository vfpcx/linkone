# 01 · 技术栈与依赖选型（v1）

> 项目：仓储云（通用仓储 SaaS 平台）
> 版本：v1 · 2026-06-02
> 编写：架构师 Agent
> 依赖：99-arch-decisions.md / PRD D09 / D10
> 状态：草案 → 待 Team Lead 复核

---

## 0. 文档说明

本文档锁定项目的技术栈、核心依赖、版本约束、第三方服务选型。所有版本号选择遵循 LTS 优先、社区活跃、与团队熟悉度匹配 三原则。

阅读对象：
- 后端开发 Agent：依据本文档初始化项目骨架
- 前端开发 Agent：依据本文档 §3/4 初始化前端项目
- 测试 Agent：依据本文档准备测试工具链
- Team Lead：依据本文档采购云资源 / 接入第三方服务

---

## 1. 平台架构总览

| 层 | 选型 | 锁定决策 |
|---|---|---|
| 端层 | PC 浏览器 + H5（移动浏览器） + 微信小程序（uni-app 编译） | D09 |
| 接入层 | Nginx 1.24 + WAF（阿里云）+ CDN（OSS public bucket） | ADR-006 |
| 应用层 | Java 21 + Spring Boot 3.2 单体多副本 | D09 / D10 / ADR-006（v2: 17→21 升级） |
| 数据层 | MySQL 8.0（主从）+ Redis 7.2（Sentinel）+ OSS（私有 Bucket） | D09 / ADR-005 |
| 中间件 | RocketMQ 5.x + XXL-Job 2.4 | ADR-008 |
| 第三方 | 阿里云短信 / NLS / OSS + 高德地图 | ADR-002 ~ 005 |
| 运维 | Docker + docker-compose（试点期）/ K8s（v2） | 06 部署 |

---

## 2. 后端技术栈

### 2.1 运行时与框架

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **OpenJDK 21 LTS** | LTS 至 2031；Virtual Threads / Pattern Matching / Sequenced Collections |
| Spring Boot | 3.2.x（最新稳定）| 3.x 系列已稳定 1.5 年；JDK 21 兼容 |
| Spring Framework | 6.1.x（跟随 Boot） | — |
| Spring Cloud | **不引入**（D10 模块化单体） | 仅在分布式事务/服务发现需要时考虑，MVP 不需要 |
| Maven | 3.6.x+ | 构建工具 |

**理由**：
- **JDK 21（v2 升级，从 17 升级）**：Virtual Threads（提升 IO 密集型并发吞吐）、Pattern Matching for Switch（正式）、Sequenced Collections、ZGC 性能改进、安全更新到 2031 年
- Boot 3.x：Jakarta EE 9 命名空间已普及，向前兼容性好
- 不引入 Spring Cloud：避免引入服务发现/网关等不需要的组件（D10 锁定单体）

### 2.2 持久层

| 项 | 版本 | 说明 |
|---|---|---|
| MyBatis-Plus | 3.5.x | 含 BaseMapper、逻辑删除、分页插件、SQL 拦截器 |
| MyBatis | 3.5.x（跟随 Plus） | — |
| HikariCP | 5.x（Boot 默认） | 连接池 |
| Flyway | 10.x | 数据库 migration（V1__init.sql、V2__add_xxx.sql） |
| MySQL Driver | mysql-connector-j 8.x | — |

**配置约束**：
- MyBatis-Plus 全局配置：逻辑删除字段统一 `deleted_at`、字段填充自动注入 `created_at` / `updated_at` / `created_by`
- HikariCP 连接池：min=10, max=50（试点期），slow-query 阈值 500ms
- Flyway：所有 DDL/DML 变更走 migration，禁止手工改库

### 2.3 鉴权与会话

| 项 | 版本 | 说明 |
|---|---|---|
| Sa-Token | 1.38.x | 鉴权框架，详见 ADR-010 |
| sa-token-redis-jackson | 同上 | Redis 会话存储 |
| sa-token-spring-boot3-starter | 同上 | Boot 3 集成 |
| BCrypt | spring-security-crypto 内置 | 密码 hash cost ≥ 10（与 PRD §16.2 对齐） |
| Hutool | 5.8.x | 工具类（Snowflake、加解密、文件操作等） |

**多账户体系约定**：
- `StpUtil`：默认体系，覆盖 TA / WK / ST / WA / WE 内部角色（同手机号同租户内多角色共享 token）
- `StpOpsUtil`（namespace=ops）：OPS 平台体系
- `StpRtUtil`（namespace=rt）：终端 RT 体系，免密验证码登录

### 2.4 缓存与中间件

| 项 | 版本 | 说明 |
|---|---|---|
| Redis | 7.2 | 缓存、Session、单据号序列、容量快照、短信验证码、分布式锁 |
| spring-data-redis | 跟随 Boot | — |
| Redisson | 3.27.x | 分布式锁（库存扣减、批量调价并发控制） |
| RocketMQ | 5.x（社区版） | 异步消息（账单生成、短信发送、通知推送） |
| rocketmq-spring-boot-starter | 2.3.x | — |
| XXL-Job | 2.4.x | 定时任务调度（DailySnapshot、容量快照、临期扫描等） |

**配置约束**：
- Redis：Sentinel 模式（主 + 2 从），密码强制；db 划分：0=session, 1=cache, 2=lock, 3=seq
- RocketMQ：3 个 topic：`notification` / `bill-generate` / `voice-asr`
- XXL-Job：嵌入式调度器，与应用部署在同 K8s 命名空间

### 2.5 文件与对象存储

| 项 | 版本 | 说明 |
|---|---|---|
| aliyun-sdk-oss | 3.17.x | OSS 客户端 |
| aliyun-sdk-sts | 3.1.x | STS 临时令牌 |
| Thumbnailator | 0.4.x | 图片压缩（入库照片缩略图） |

### 2.6 短信与语音

| 项 | 版本 | 说明 |
|---|---|---|
| aliyun-java-sdk-dysmsapi | 2.2.x | 阿里云短信 |
| nls-sdk-recognizer | 2.2.x | 阿里云一句话识别 |
| nls-sdk-transcriber | 2.2.x | 阿里云实时语音识别 |
| tencentcloud-sdk-sms | 3.1.x（v2 灰度备份） | 腾讯云短信（容灾） |

### 2.7 地图

| 项 | 版本 | 说明 |
|---|---|---|
| 高德 Web 服务 API | HTTP 调用，无 SDK | 后端逆向地理编码、距离计算、IP 定位 |
| Apache HttpClient | 5.x | 调高德 Web API |

> 前端地图见 §3.5。

### 2.8 业务工具

| 项 | 版本 | 说明 |
|---|---|---|
| MapStruct | 1.5.x | DO ↔ DTO 映射，编译期生成不损耗运行性能 |
| Lombok | 1.18.x | 简化 POJO |
| FastJSON2 | 2.0.x | JSON 序列化（性能优于 Jackson） |
| EasyExcel | 3.3.x | 账单 Excel 导出（POI 流式封装） |
| iText | 8.x 或 Flying Saucer | PDF 导出（账单/出库单/入库单） |
| Disruptor | 4.x | 操作日志异步队列（ADR-014） |
| caffeine | 3.x | 本地缓存（SPU、规格类型等低频变动数据） |

### 2.9 监控与日志

| 项 | 版本 | 说明 |
|---|---|---|
| Logback | 跟随 Boot | 日志框架 |
| logstash-logback-encoder | 7.x | JSON 日志格式，便于 ELK 收集 |
| Micrometer | 跟随 Boot | Metrics 采集 |
| Spring Boot Actuator | 跟随 Boot | 健康检查 + Metrics 端点 |
| SkyWalking Agent | 9.x（可选） | APM 追踪 |

### 2.10 测试

| 项 | 版本 | 说明 |
|---|---|---|
| JUnit | 5.10.x | 单元测试 |
| Mockito | 5.x | Mock |
| AssertJ | 3.x | 断言库 |
| Testcontainers | 1.19.x | 集成测试（启 MySQL/Redis 容器） |
| WireMock | 3.x | 第三方服务 Mock（阿里云/高德） |
| MyBatis-Plus Test | 跟随主版本 | DAO 测试 |
| RestAssured | 5.x | API 测试 |

### 2.11 代码规范

| 项 | 版本 | 说明 |
|---|---|---|
| Checkstyle | 10.x | 代码风格（阿里 P3C 规范裁剪） |
| SpotBugs | 4.x | 静态分析 |
| Jacoco | 0.8.x | 覆盖率（单元测试 ≥ 70%，关键业务 ≥ 85%） |

---

## 3. 前端技术栈

### 3.1 PC 端（OPS / TA / ST 主操作台）

| 项 | 版本 | 说明 |
|---|---|---|
| Vue | 3.4.x | 主框架（D09） |
| Vite | 5.x | 构建工具 |
| TypeScript | 5.x | 强制类型 |
| Pinia | 2.x | 状态管理 |
| Vue Router | 4.x | 路由 |
| Element Plus | 2.6.x | UI 组件库（B 端成熟） |
| Axios | 1.6.x | HTTP 客户端 |
| Day.js | 1.11.x | 日期处理（轻量替代 moment） |
| ECharts | 5.5.x | 图表（账单趋势 / 容量利用率 / OPS Dashboard） |
| Mermaid | 10.x（可选） | 状态机展示（单据流转可视化） |
| @amap/amap-jsapi-loader | 1.0.x | 高德 JS API 2.0 加载器 |
| xlsx | 0.20.x | 前端 Excel 解析（账单上传/导出预览） |
| vxe-table | 4.x（可选） | 大数据量表格（账单明细 1w+ 行） |

**结构约定**：
```
src/
├─ api/            // 按模块划分 axios 调用
├─ assets/
├─ components/     // 通用组件
├─ composables/    // hooks
├─ layouts/        // 角色布局（OPSLayout / TenantLayout）
├─ pages/          // 按角色 + 业务划分
│   ├─ ops/
│   ├─ tenant/
│   ├─ wholesaler/
│   └─ settlement/
├─ router/
├─ stores/         // pinia
├─ utils/
└─ main.ts
```

### 3.2 H5 + 小程序（WK / WA / WE / RT 移动端）

| 项 | 版本 | 说明 |
|---|---|---|
| uni-app | 3.x（最新 Vue3 模板） | 跨端框架（D09） |
| Vue | 3.4.x | — |
| Pinia | 2.x | 状态管理 |
| uview-plus | 3.x | uni-app UI 组件库 |
| uni.request 二次封装 | — | 统一鉴权头 |
| qiun-data-charts | 2.x | 图表组件 |
| uni-amap（uni 插件市场） | — | 高德地图 H5 + 小程序统一封装 |
| dayjs | 1.11.x | — |

**端兼容**：
- H5（默认开发主端）
- 微信小程序（PRD §RT 提及，作为入口扩展）
- App（v2，预留）

### 3.3 共享代码

OpenAPI 文档由后端 SpringDoc 输出 `openapi.json` → 前端通过 `openapi-typescript-codegen` 自动生成 TypeScript Client + 类型定义。

```
shared/openapi/
├─ openapi.json      // 后端 build 时生成
└─ types/            // 自动生成的 TS 类型，供 PC + H5 共用
```

### 3.4 构建与发布

| 项 | 版本 | 说明 |
|---|---|---|
| pnpm | 8.x | 包管理器（节省磁盘 + 速度快） |
| ESLint | 8.x | 代码风格 |
| Prettier | 3.x | 格式化 |
| Husky | 9.x | git hooks |
| commitlint | 19.x | commit message 规范 |

### 3.5 前端地图

| 项 | 版本 | 说明 |
|---|---|---|
| 高德 JS API 2.0 | 2.0 | PC + H5 |
| @amap/amap-jsapi-loader | 1.0.1 | 异步加载器 |
| amap-jsapi-types | 0.0.x | TypeScript 类型 |
| 小程序高德 SDK | 1.2.x | 微信小程序专用 |

---

## 4. 第三方服务选型详表

### 4.1 阿里云短信服务

| 项 | 配置 |
|---|---|
| 签名 | 「仓储云」（需工信部备案，3 天审核）|
| 模板 | 8 个：register / login / reset_pwd / change_phone / bill_dispatch / proxy_doc_notify / complaint / clearance |
| 计费 | ¥0.045/条，预付费 |
| 并发限频 | 单手机号 60s/条，日 10 条（PRD §16.1）|
| 容灾 | 腾讯云短信备份（3 次失败切换） |
| 决策依据 | ADR-004 |

### 4.2 阿里云 NLS（语音识别）

| 项 | 配置 |
|---|---|
| 服务 | 一句话识别（短语音 ≤60s）+ 实时语音识别（流式） |
| 模型 | 通用普通话模型（MVP）；方言模型 v2 |
| 调用方式 | WebSocket 流式 + REST API |
| 计费 | 实时识别 ¥0.0035/秒；一句话识别 ¥0.00175/秒 |
| 并发限制 | 默认 200 并发，按需扩 |
| 决策依据 | ADR-003 |

### 4.3 阿里云 OSS

| 项 | 配置 |
|---|---|
| 区域 | 华东 1（杭州）— 与 ECS 同地域 |
| Bucket × 4 | photos（永久）/ voices（30d）/ exports（7d）/ public（永久 + CDN） |
| 访问 | 私有 + STS 临时令牌（30 分钟有效） |
| 流量 | 内网（同 VPC ECS）免流量费；外网 ¥0.5/GB |
| 生命周期 | voices Bucket 自动 30 天删除 |
| 决策依据 | ADR-005 |

### 4.4 高德地图

| 项 | 配置 |
|---|---|
| 服务 | JS API 2.0（前端） + Web 服务 API（后端） |
| Key | 后端 / Web / iOS / Android 各一把（防泄漏） |
| 计费 | 企业版日 100w 免费，超额 ¥0.005/次 |
| 主要 API | 地图渲染 / 地点搜索 / 逆向地理编码 / 距离测量 / IP 定位 / 行政区查询 |
| 容灾 | 腾讯地图作灰度备份（接口抽象） |
| 决策依据 | ADR-002 |

### 4.5 阿里云 ECS / RDS / Redis（运维）

详见 06 部署 §容量估算。

---

## 5. 项目模块依赖（Maven 父子结构）

```
cangchu-cloud/                            (parent pom)
├─ cangchu-bom/                           (依赖版本统一管控)
├─ cangchu-common/                        (工具类、异常、通用 DTO)
├─ cangchu-infrastructure/                (通用基础设施)
│   ├─ infra-mybatis/                     (MyBatis-Plus 拦截器、TenantInterceptor)
│   ├─ infra-redis/                       (Redis 工具)
│   ├─ infra-mq/                          (RocketMQ 封装)
│   ├─ infra-oss/                         (OSS 工具 + STS)
│   ├─ infra-sms/                         (短信抽象 + 阿里云/腾讯云实现)
│   ├─ infra-asr/                         (ASR 抽象 + 阿里云实现)
│   ├─ infra-map/                         (地图抽象 + 高德/腾讯实现)
│   └─ infra-log/                         (操作日志 AOP + Disruptor)
├─ cangchu-domain/                        (业务领域模块，与 02-modules 对齐)
│   ├─ domain-account/                    (账号 / Sa-Token / 注册码 / 短信码)
│   ├─ domain-tenant/                     (租户 / 店铺 / 计费规则 / 开关)
│   ├─ domain-wholesaler/                 (批发商 / 入驻 / 黑名单)
│   ├─ domain-product/                    (SPU / SKU / 规格 / 价格)
│   ├─ domain-inventory/                  (库存 / 批次 / 流水 / 托盘)
│   ├─ domain-document/                   (入库 / 出库 / 询价 / 退货 / 盘点 / 清库)
│   ├─ domain-billing/                    (账单 / 快照 / 回款 / 申诉)
│   ├─ domain-matchmaking/                (撮合店铺页 / 容量公示)
│   ├─ domain-notification/               (站内信 / 短信 / 推送)
│   ├─ domain-file/                       (附件 / 入库照片)
│   ├─ domain-voice/                      (语音录音 / 转写 / NLU)
│   └─ domain-platform/                   (公告 / 客诉 / OPS 操作)
├─ cangchu-app/                           (启动入口 + API 层)
│   ├─ app-api-ops/                       (OPS 接口)
│   ├─ app-api-tenant/                    (TA / WK / ST 接口)
│   ├─ app-api-wholesaler/                (WA / WE 接口)
│   ├─ app-api-retail/                    (RT 接口)
│   └─ app-bootstrap/                     (main + 配置)
└─ cangchu-tests/                         (集成测试)
```

> 当前模块化单体，所有 domain 模块编译到同一 JAR 启动；只是源码与依赖按业务域隔离，防止循环依赖。

---

## 6. 配置中心与环境

### 6.1 配置层级

| 层 | 文件 | 用途 |
|---|---|---|
| 公共 | application.yml | 跨环境共用配置 |
| 环境 | application-{dev,test,prod}.yml | 环境差异 |
| 本地 | application-local.yml（.gitignore） | 本地覆盖 |
| 远端 | Apollo / Nacos（v2） | MVP 暂用本地 yml，v2 引入配置中心 |

### 6.2 关键配置项

```yaml
cangchu:
  tenant:
    leak-detection: true          # 多租户隔离强校验，prod 必须 true
  jwt:
    timeout: 28800                # Web 8 小时（与 PRD §16.3 对齐）
    timeout-h5: 2592000           # H5 30 天
  sms:
    primary: aliyun
    fallback: tencent
    retry: 3
  asr:
    primary: aliyun
    max-duration-sec: 60
  oss:
    region: cn-hangzhou
    bucket-photos: cangchu-cloud-prod-photos
    bucket-voices: cangchu-cloud-prod-voices
    bucket-exports: cangchu-cloud-prod-exports
    bucket-public: cangchu-cloud-prod-public
  map:
    provider: amap
    amap-key-web: ${AMAP_KEY_WEB}
    amap-key-server: ${AMAP_KEY_SERVER}
  billing:
    job-cron: "0 0 0 1 * ?"       # 每月 1 日 0:00
    daily-snapshot-cron: "0 0 0 * * ?"
  capacity:
    refresh-cron: "0 */10 * * * ?"
    cache-ttl-sec: 1800
```

### 6.3 敏感信息

- 数据库密码、Redis 密码、OSS AK/SK、阿里云 AK/SK、高德 Key 全部存阿里云 KMS
- 应用启动时从 KMS 取 → 注入 Spring Environment
- 任何配置文件不允许明文存敏感信息（CI 流水线扫描）

---

## 7. CI/CD 工具链（详见 06 部署）

| 项 | 说明 |
|---|---|
| 代码仓库 | GitLab（私有部署，与团队现有一致） |
| CI | GitLab CI（pipeline 文件 `.gitlab-ci.yml`） |
| 构建产物 | Docker 镜像 → 阿里云容器镜像服务（ACR） |
| 制品库 | Nexus 3（私有 Maven / npm） |
| 部署 | Ansible playbook（试点期）/ Helm Chart（v2） |
| 监控 | Prometheus + Grafana + AlertManager |
| 日志 | ELK（Filebeat → Logstash → ES → Kibana） |
| APM | SkyWalking（可选，v2） |

---

## 8. 版本统一原则

| 原则 | 说明 |
|---|---|
| BOM 锁版本 | cangchu-bom 模块统一管理所有依赖版本，业务模块只引 GAV 不写版本 |
| Spring Boot 升级窗口 | 跟随 Spring Boot 3.x patch（每季度评估） |
| 第三方 SDK 升级 | 每季度评估安全公告 + CVE |
| 禁止 SNAPSHOT 进生产 | CI 检查 |
| 多语言 lint | 提交前 mvn checkstyle + npm run lint 双侧通过 |

---

## 9. 关键依赖兼容矩阵

| 组合 | 验证状态 |
|---|---|
| JDK 21 + Spring Boot 3.2 + MyBatis-Plus 3.5 | ✅ Spring Boot 3.2 起官方支持 JDK 21 |
| Spring Boot 3.2 + Sa-Token 1.38 | ✅ 官方适配 starter |
| RocketMQ 5.x + Spring Boot 3.x | ✅ 官方 starter 2.3+ |
| Vue 3.4 + Element Plus 2.6 + Vite 5 | ✅ 主流组合 |
| uni-app + Vue 3 + Pinia | ✅ uni-app 3.x 起官方支持 |

不兼容组合（避免）：
- Spring Boot 3.x + MyBatis-Plus 3.4.x（必须 3.5+）
- Vue 3 + Element UI 2.x（要 Element Plus）
- uni-app + Vuex 4（推荐 Pinia）

---

## 10. 团队所需新技能 + 培训建议

| 技能 | 团队当前 | 培训建议 |
|---|---|---|
| Sa-Token | 不熟悉 | 1 天集中培训 + 官方文档 |
| RocketMQ | 不熟悉 | 0.5 天集中培训 |
| uni-app 跨端开发 | 部分熟悉 | 官方文档 + Demo 项目 |
| 高德 JS API 2.0 | 不熟悉 | 0.5 天 |
| 阿里云 NLS | 不熟悉 | 0.5 天（含联调） |
| MyBatis-Plus 拦截器（多租户） | 部分熟悉 | 0.5 天（强制全员） |

---

## 11. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| JDK 21 团队不熟悉（含 Virtual Threads） | 中 | 选择 Spring Boot 3.2 主流稳定版；培训；MVP 阶段先用平台默认线程池，VT 在 v2 评估接入 |
| Sa-Token 文档英文较少 | 低 | 中文社区活跃；问题响应快 |
| 阿里云全家桶绑定深 | 中 | infra 模块抽象接口，预留切换路径 |
| uni-app 小程序 + H5 兼容差异 | 中 | 优先 H5 主端；小程序按 v2 节奏适配 |
| 模块化单体编译时间长 | 低 | 多模块拆分；按需编译；CI 缓存 |

---

## 12. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-02 | 首版 |

---

> 下一步：02-modules.md（模块边界与依赖关系）
