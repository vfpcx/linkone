# 上线检查单 · 命令化执行版（X 期余项，待正式环境逐项执行回填）

> 2026-09-08 F7-6 由「task_plan 验收条目」命令化落库。每项含：命令/配置点 → 期望 → 状态回填。
> 真源：`06-dependency-cve-scan.md` §7.4（CVE）、`13-pii-w8-delivery-report.md` §8.5（停服手册/状态表）、
> `11-hardening-design.md` §2.2 与 `application-prod.yml` 头注释（Redis ACL/密码）、prod 冒烟口径见 13 §8.5 W8-L3 行。
> 状态列惯例：⬜ 待执行 / 🟡 进行中 / ✅ 通过（回填日期）。

## 0. 前置说明

- prod profile **故意无默认密钥**：`MYSQL_PASSWORD` / `REDIS_PASSWORD` / `PII_HMAC_KEY` / `PII_HMAC_KAT` / `PII_DEK_V1` / `PII_CIPHER_KAT` 缺失即启动失败（fail-fast），见 `application-prod.yml`。
- 所有命令在**仓库根**（`superpowers-collab/`）或标注目录执行；工具装一次即可。

## 1. CVE 复扫工具门禁（W8-L4 收尾）

```powershell
powershell -ExecutionPolicy Bypass -File shared/ops/cve-scan.ps1
# 逐路跳过（如某工具暂缺）：-SkipOwasp / -SkipTrivy / -SkipOsv
# OWASP 首次会下载 NVD 数据，耗时较长；报告归档 backend/target/cve-scan/<ts>/
```

- 期望：三路全 OK；若 FAIL/WARN → 对照 `test-plan/06-dependency-cve-scan.md` §7 逐条评估（基线 165 依赖，Tomcat 10.1.59 等已修项排除），新披露回填 §7 后再放行。
- 状态：⬜（工具安装：`mvn`(已有) + `trivy`(aquasecurity) + `osv-scanner`(google/osv-scanner)）

## 2. prod 冒烟（W8-L3）

```powershell
# ① fail-fast 负路径：缺任一密钥应启动失败（先验证护栏本身在生效）
#    故意不设 PII_DEK_V1 再启动 → 期望启动报错退出，非静默裸奔
cd backend
$env:SPRING_PROFILES_ACTIVE='prod'
# ② 正路径：注入全部环境变量后启动（值由部署脚本/密管提供，不进 git）
$env:MYSQL_URL='jdbc:mysql://<host>:3306/<db>'; $env:MYSQL_USER='<u>'; $env:MYSQL_PASSWORD='<p>'
$env:REDIS_HOST='127.0.0.1'; $env:REDIS_PORT='6379'; $env:REDIS_PASSWORD='<p>'
$env:PII_HMAC_KEY='<hmac 密钥>'; $env:PII_HMAC_KAT='<hmac KAT 期望>'
$env:PII_DEK_V1='<AES-GCM 数据密钥>'; $env:PII_CIPHER_KAT='<cipher KAT 期望>'
mvn spring-boot:run
```

- 期望：启动日志含「Started …Application」且 **PII KAT 通过**（密钥配错在启动期 fail-fast）；`curl http://localhost:8080/actuator/health` → `{"status":"UP"}`。
- 三链路冒烟：① TA 注册/登录（roles+路由契约）② RT 进店→提交询价（storefront 匿名链）③ PII reveal 查全号（`GET /pii/phone-reveal`，登录态解密出**明文**且审计留痕）——期望全 code=0。
- 状态：⬜

## 3. graceful shutdown 人工停服实测（W8-L5 收尾 · 手册 13 §8.5.2 命令化）

前置：`application.yml` 已含 `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`；**服务须以本次配置启动**。

```powershell
# ① 正常停服 = Ctrl+C（等价 SIGTERM → JVM shutdown hook）
#    Windows 服务注册后必须用 sc stop <service> / 服务管理器（SCM 控制台事件可触发 hook）；
#    禁止用 Stop-Process / taskkill /f（TerminateProcess 强杀，不触发优雅停机）
# ② 观测（停服前可另开终端发一个在途请求，验证在途保护）
```

- 期望 4 项：日志 `Commencing graceful shutdown. Waiting for active requests to complete` → 新请求停止受理 → 无在途即退/有在途 ≤30s 完成后退 → 8080 释放（`Get-NetTCPConnection -LocalPort 8080` 无 LISTEN，curl 拒绝连接）。
- 超时负路径（边界认知）：在途 >30s 日志应现 `Timeout during graceful shutdown` 后强制退出（本项目导出量级不触发）。
- 状态：⬜（通过后回填 13 §8.5 表 W8-L5 → ✅）

## 4. Redis 实际启用密码 + ACL（W8-L6）

目标态（真源：`11-hardening-design.md` §2.2 + `application-prod.yml` 注释；对齐 P4-L5）：
**bind 回环 + protected-mode + requirepass + ACL 应用账户**（`user default off`；`user cangchu_app on ><密码> ~* &* +@all -@admin -@dangerous`）。

```bash
# Redis 服务端（生产 Linux 或 Memurai/Windows 等效），redis-cli 或改 redis.conf：
CONFIG SET protected-mode yes
CONFIG SET bind 127.0.0.1            # 或内网可控网段；禁止 0.0.0.0
CONFIG SET requirepass <管理员密码>
ACL SETUSER cangchu_app on ><应用密码> ~* &* +@all -@admin -@dangerous
ACL SETUSER default off              # 兜底：禁用默认用户（应用一律走 cangchu_app）
# 持久化：上述 CONFIG SET 仅运行时生效，须同步写回 redis.conf / Memurai 配置
```

- 应用侧：后端以 `REDIS_PASSWORD=<应用密码>` 启动（prod 从 `spring.data.redis.password` 继承认证）；**仅 requirepass 阶段不要设 `username`**（`spring.data.redis.username` 注释保持，AUTH 用户仅在 ACL 阶段放开）——见 `application-prod.yml` 注释。
- 期望：① 无密码客户端连接被拒（`AUTH required`）② 后端启动成功 + 登录/Sa-Token 会话可用（Redisson 走 ACL 账户，`pingConnectionInterval` 稳态参数不受影响）③ 用 `default` 或危险命令（`FLUSHALL`/`EVAL`）被 ACL 拒绝。
- 状态：⬜（通过后回填 13 §8.5 表 W8-L6 → ✅ 及 roadmap X 行）

---

## 回填位（全部通过后）

| 位置 | 内容 |
|---|---|
| `test-plan/13-pii-w8-delivery-report.md` §8.5 状态表 | W8-L2/L3/L4/L5/L6 → ✅（L2 观察期如无生产观测对象按 W6 先例拍板） |
| `test-plan/06-dependency-cve-scan.md` §7.4 | 门禁执行结果 + 新披露逐条 |
| `00-roadmap.md` X 期与 D 波行 | 余项 → ✅ |
| `shared/task_plan.md` | 验收条目回填 |
