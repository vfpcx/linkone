# 09 · X 硬化第一批交付报告（H4/H3/H2 配置 + H5 Boot 3.5.16 升级）

> 角色：后端开发 Agent ｜ 日期：2026-07-29 ｜ 分支：`chore/hardening-boot-upgrade`
> 依据：`shared/architecture/11-hardening-design.md`（H2/H3/H4/H5）+ `06-dependency-cve-scan.md`（TOP1/TOP2）
> 范围红线：PII 三段式（H1）本批不做；blacklist hmac 口径为过期信息，未采用。

## 1. 交付 commits（分步，未合并）

| commit | 内容 |
|---|---|
| 9e75f50 | H4 日志 profile 化：主配 NoLoggingImpl + INFO/WARN + is-log false；dev 显式放开；logback springProfile 拆分；3 处明文手机号 log.info 收口 SmsUtil.maskPhone |
| 2938754 | H3 active-timeout：主配 1800s（prod 安全默认），dev/test 覆盖 -1；新增 SessionActiveTimeoutTest（HD-H3-01 冻结→401/41001、HD-H3-02 活跃续活） |
| 76c8298 | H2 Redis 密码路径：新增 application-prod.yml（REDIS_PASSWORD/MYSQL_* env 注入无默认值 fail-fast；ACL username 留注释；Actuator 收敛 health） |
| cbe9b0a | H5 Boot 3.2.5→3.5.16 + MP 3.5.17(+mybatis-plus-jsqlparser) + sa-token 1.45.0 + redisson 3.52.0 + flyway 回归 BOM |
| c8a16f1 | merge main（72a5308 缺陷批+refactor）——零冲突，升级态全量回归 |

## 2. 测试与冒烟

- `mvn clean test`：**250/250 绿，0 失败 0 跳过**（main 基线 248 + 本批新增 2）。
  含 TenantScenarioTest（TN-S4 租户隔离，jsqlparser 5.2 直接风险点）与 pricing 全量。
- 升级**零业务代码改动**：编译零错误零弃用告警；RedissonAutoConfigurationCustomizer（Bug A 稳态参数）在 3.52.0 API 兼容。
- dev,local 启动冒烟（Boot 3.5.16 运行态，测试套件不覆盖的链路）：
  - Flyway 11.7.2 对既有 18 个迁移 validate 通过、无需迁移（10→11 history 兼容确认）；
  - 注册全链 code=0（MySQL 写入 + BCrypt(security-crypto 6.5.11) + sa-token 1.45 会话经 Redisson 3.52 写入 Memurai）→ 登录态探针 code=0。

## 3. 4 高危组件版本对照（mvn dependency:tree 实测）

| 组件 | 升级前 | 升级后 | 安全线 | 判定 |
|---|---|---|---|---|
| tomcat-embed-core | 10.1.20 | **10.1.55** | ≥10.1.45（CVE-2025-24813 在野 RCE 等 7 项） | ✅ |
| spring-security-crypto | 6.2.4 | **6.5.11** | ≥6.4.4（CVE-2025-22228 BCrypt>72 字符） | ✅ |
| Spring Framework (spring-web/core) | 6.1.6 | **6.2.19** | ≥6.1.21（CVE-2024-38816/38819 等） | ✅ |
| netty-common | 4.1.109 | **4.1.135** | ≥4.1.125（CVE-2025-24970/58056/58057） | ✅ |

其他随批：logback 1.5.34（≥1.5.13 ✅）、Jackson 2.21.4、H2 2.3.232（测试建表零调整）、mysql-connector-j 9.7.0（与服务端 9.7 对齐）、flyway 11.7.2、fastjson2 2.0.62（代码 grep 确认未开 SupportAutoType）。

依赖树归档（下次扫描基线，CVE 报告 §6.3）：
`dependency-tree-before-boot325.txt` / `dependency-tree-after-boot3516.txt`

## 4. 遗留风险 / 待办

1. **prod profile 未实机冒烟**：需 Memurai 侧先配 requirepass（+可选 ACL，11 §2.2）后按上线检查单验证三链路（会话/RLock+Lua/RAtomicLong）与 fail-fast 行为；本机 Memurai 未启密码（按批次约定不强制）。
2. **Redis 6379 监听 0.0.0.0**（netstat 实测）：上线前必须 bind 127.0.0.1 + protected-mode（11 §2.2 网络面），属部署配置。
3. Boot 3.4 起 graceful shutdown 默认开启：Windows 服务停止行为未实测，上线检查单登记。
4. CVE 复扫门禁（OWASP dependency-check / Trivy）未执行（本批不改 pom 加插件的既定约定）；以 §3 表 + 归档树为基线复扫。
5. E2E（前端 Playwright）本批未跑，随波次合并回归执行。
6. sa-token 1.42+ 官方推荐 redis 集成迁至 sa-token-redis-template 系；1.45.0 的 sa-token-redis-jackson 仍正常发布且冒烟通过，中期可择机切换。
