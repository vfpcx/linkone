# 11 · P3b 交付验收报告（W5 终验收）

> 测试&审查 Agent · 2026-08-04 · 验收基线 main=afb9b26（P3b 八开发波全合并）
> 上期基线：10-p3-delivery-report.md（main=75fe3f1，250 测试 / E2E 29 例）

## 1. P3b 范围

三条主题线（真源 product/10-p3b-requirements.md v1.1 十四项 DECISION + 11-p3b-prd.md + architecture/13-p3b-design.md）：

- **T1 正向入库申请链**：WA 表单提交 → WK 受理/驳回 → 登记（5% 边界）→ 撤回（仅 SUBMITTED）→ 纠错（CORRECTION_IN/OUT 配对、封顶）；WE 授权位 INBOUND_SUBMIT；SKU 列表放行 WK
- **T3 退货 + 盘点**：RTN- 退货（D-7 登记时扣）、PD- 盘点全链（D-10 盘亏封顶）、D-8=A 托盘释放收口（PALLET_RELEASE 流水）、batch_enabled 禁改（D-13）
- **T4 批次临期**：D-11=C 批次登记簿 + FIFO 离线推算（交易路径零改动）；V22/V23 迁移、02:00/02:30 双 Job、临期看板、手动通知 24h 冷却（50367）、QK- 清库全链、过期入库凭据放行（50364）
- 错误码 50350-50369；Flyway V19-V23

## 2. 九波闸门数据（W0 + 8 开发波，均 Team Lead 独立复验后合并）

| 波次 | 合并 commit | 闸门 |
|---|---|---|
| W0 设计定稿 | （文档）| PRD 11 + 架构 13 契约词表对账无漂移 |
| T1-BE | 4d93c5c | 270 绿（V19/九端点/5% 边界/纠错/INBOUND_SUBMIT） |
| T1-FE | 95cf91d | E2E 3/3 + 12 截图亲检 |
| T3-W1 | 321ef14 | 285 绿（V20/RTN-/pallet_delta 五类双写/batch 禁改） |
| T3-W2 | 75cc6c6 | 303 绿（V21/PD- 全链/盘亏封顶/代建托盘收口） |
| T3-FE | 32ea629 | E2E 3/3 + 回归 4/4 + 10 截图亲检 |
| T4-W1 | e594369 | 318 绿（V22/登记簿零侵入/FIFO 推算/默认批吸收） |
| T4-W2 | 80e5dd9 | 337 绿（V23/双 Job/D-12 去重/QK- 全链/看板端点） |
| T4-FE | afb9b26 | E2E 3/3 + 回归 6 例 + typecheck + 10 截图亲检 |

## 3. W5 后端全量回归（337 例 × 4 遍）

| 轮次 | 代码态 | 结果 |
|---|---|---|
| Run 1 | 修复前基线 | **337 / 0 fail / 0 error / 0 skip，BUILD SUCCESS** |
| Run 2-4（连跑） | 简码稳定化修复后 | **均 337 全绿，BUILD SUCCESS ×3** |

环境注记：仓内 `backend/mvnw` 的 wrapper jar 缺失（`ClassNotFoundException: MavenWrapperMain`，`.mvn/wrapper/` 目录不存在），mvnw 退出码仍为 0 属误绿——本轮及历轮实际均以系统 mvn 3.6.3 + JDK 21 执行；建议后续补齐 wrapper 或删除 mvnw 脚本避免误用（遗留 L-6）。

### 3.1 抖动①：租户简码碰撞 → 已根治（测试侧）

- **根因**：7 个场景链测试类共用 `"T" + (snowflakeId % 1_000_000)` 造租户简码（另有 W/B/Q 前缀取模 3 处），同一 JVM 共享 H2 下全量累计数百租户，雪花低 6 位十进制按生日悖论约 1-2%/轮 概率撞 `uk_simple_code` 唯一键。
- **修复**：新增测试工具 `com.cangchu.common.TestUniq`（JVM 全局 AtomicInteger，产 `Z%07d` 8 字符简码，与现存 T/S/R/B/Q/W 前缀互斥），替换全部 10 处取模造数（10 个测试类）。跨类绝对无碰撞，纯测试侧改动，业务代码零触碰。
- **证明**：修后连跑 3 遍全量 337 全绿。

### 3.2 抖动②：H2 并发连接 → 本轮零再现

concurrentWithdraw 三件套先例（7fc7ce2：LOCK_TIMEOUT=10000 + 测试池加大 + 仅限基建故障受控重试）保持不动；本轮 4 遍全量未再现（`[W5-flake-retry]` 打点无输出），按 10 报告 §4-4 机制留档，不扩大重试面。

## 4. E2E 全套（38 例全绿，3.9m）

前置：8080 后端与 5173 前端均重启至最新 main 后执行（原 8080 实例早于合并、原 5173 vite 跑在已删除的 worktree 目录——均已纠正，见 §7）。

| spec | 用例 | 结果 |
|---|---|---|
| auth | E1-E8（注册/登录/找回/工作台/退出/负向×2/幂等） | 8/8 ✅ |
| sell-flow | S1×2 / S2×2 / S6 | 5/5 ✅ |
| sell-flow-2 | B-RT-02/03/07、B-WA-04、B-EMP-02 | 5/5 ✅ |
| onboarding-flow | ONB-E2E-01~04（入驻/黑名单/退驻/WE 员工） | 4/4 ✅ |
| inbound-dispute | INB-01~03（确认/异议+附件/仲裁） | 3/3 ✅ |
| outbound-chain | OUT-01~04（主链/直撤/已打印撤回/客诉裁决） | 4/4 ✅ |
| p3b-inbound-forward | FWD-01~03（主链 5% 边界/驳回+复制重建/纠错封顶） | 3/3 ✅ |
| p3b-t3-returns-stocktake | RTN-01、PD-02、PD-03 | 3/3 ✅ |
| p3b-t4-batch-expiry | T4-01~03（默认批吸收/看板+冷却/过期清库全链） | 3/3 ✅ |
| **合计** | | **38/38 ✅，零环境失败零真缺陷** |

## 5. 视觉矩阵（p3b-w5-visual.spec.ts，17 图，逐张目检）

产物：`.e2e-tmp/p3b-w5-visual/`（供 Team Lead 亲检）。16/16 用例绿。

- **未登录三页 × 390/375**（6 图）：布局无溢出/无错位；已知 V-4 残留不变——375 宽下 register/forgot「请输入短信验证码」placeholder 末字截断（cosmetic）；register@375「《隐私政策》」换行属正常流式换行非缺陷。
- **TA @1280**（dashboard/returns/stocktake/batches/clearance）：P3b 四新页全部对齐良好、空态规范、口径文案完整（登记时扣/推算值·截至 02:00/封顶说明均在页头）；菜单 12 视图统一含角标。dashboard 仍为 mock（V-2 已知，页脚已标注待 `/tenant/dashboard` 联调）。
- **WA @1280**（inquiry/inbound-apply/returns）：双视图页签、72h 倒计时、黄条口径文案正常。**发现（低）**：代建入库确认列表 SKU 列仍显裸雪花 ID（V-3 残留面之一；T1 已在 Outbound.vue 换回名称，本页未随改）→ 记 L-5。
- **WK/OPS @1280**（wk-outbound-workbench/ops-dashboard/ops-arbitrations）：正常。OPS 占位页已中文化（roleLabel「平台运维」，10 报告 V-1 的裸角色码直出已清理）。**发现（极低）**：ops-arbitrations 发起时间列在默认列宽下时间截断（`12:08:` 后被裁）→ cosmetic，记 L-7。

**零角色码 grep 复核（全前端 src）**：模板中文邻接角色码模式扫描——用户可见文案裸角色码直出 **0 处**（原唯一命中 V-1 占位页已改 roleLabel 中文化）；3 处「中文（码）」注册码类别标注（员工页 WK/ST、WA 员工页 WE）属映射说明式文案，与 P3 验收口径一致不计违规；其余命中均为代码注释/HTML 注释。P3b 六新页零命中。

## 6. 遗留清单

| # | 项 | 说明 | 级别 |
|---|---|---|---|
| L-1 | **batch-config 可读端点缺口** | `TenantService.getBatchConfig`（batchEnabled/阈值）仅服务内部用，无 GET 端点；前端 Settings/看板阈值目前依赖 TenantDetail 携带与「去设置」间接读取，无法独立拉取批次配置 → 建议 P4/P5 补 `GET /tenant/batch-config` | 中 |
| L-2 | **撤回→库管通知未实现** | `withdrawByWa`（InboundRequestServiceImpl:521）撤回成功仅落库+日志，无通知 WK；WK 若已备货会白跑（受理锁单 50350 只堵了状态机，不堵信息差） | 中 |
| L-3 | **WA 临期下钻未做** | PRD 11 §3.6-C 批发商端「临期卡→批次下钻」（G6 简版）未实装；当前 WA 仅收通知（站内信），无自助批次视图 | 中 |
| L-4 | **PRD 补录归属修订** | PRD §3.6 写「默认批次保质期由批发商管理员后续补录」，实现为 TA 登记簿补录（T4-FE 落地位置）——需产品把 PRD 归属改为 TA/WK 或下期给 WA 补录入口，二选一收口 | 低（文档） |
| L-5 | **WA 入库确认 SKU 裸 ID** | V-3 残留面：wa/Inbound.vue 代建确认列表 SKU 列显示雪花 ID；Outbound.vue 已换回名称，本页未随改 | 低 |
| L-6 | **mvnw wrapper 失效** | `.mvn/wrapper` 缺失致 mvnw 报 ClassNotFoundException 且退出码 0（误绿风险）；统一用系统 mvn 或补 wrapper | 低 |
| L-7 | **ops-arbitrations 时间列截断** | 默认列宽下发起时间被裁，cosmetic | 极低 |

### P3 上线检查单余项复核（10 报告 §4 六项）

1. **Redis 监听**：本轮实测已改绑 `127.0.0.1:6379`（较 P3 期 0.0.0.0 收口）；requirepass/protected-mode 及 prod 三链路冒烟仍未验 → 上线前仍需执行。
2. **WK SKU 名称端点**：T1-BE 已放行 WK + Outbound.vue 换回名称 → 主体收口；残留见 L-5。
3. **打印视图 ID**：随 2 主体收口（票面走 Outbound.vue）。
4. **H2 抖动机制**：本轮 4 遍零再现，机制留档不变。
5. **graceful shutdown（Windows）**：仍未实测 → 保留。
6. **CVE 复扫门禁**：仍未执行 → 上线前必做（基线 dependency-tree-after-boot3516.txt）。

## 7. 环境与服务（验收后保持运行）

- **后端** http://localhost:8080 —— main=afb9b26，`mvn spring-boot:run -Dspring-boot.run.profiles=dev,local`，health UP；日志 `.e2e-tmp/backend-w5.log`
- **前端** http://localhost:5173 —— 主仓 `frontend/apps/admin` vite dev（已纠正原「已删除 worktree 目录」实例）；日志 `.e2e-tmp/vite-dev.log`
- MySQL cangchu_dev + Redis(Memurai) 127.0.0.1:6379 在跑

## 8. 下一期建议

1. **P4 账单/计费波顺带**：`/tenant/dashboard` 真端点（清 V-2 mock）+ L-1 batch-config 读端点 + L-5 SKU 名称统一（一次契约补口清三项）。
2. **通知补全小批**：L-2 撤回通知 + 10 报告遗留的 WE 出库定位（C11）可合并为一个通知/权限小波。
3. **WA 侧批次自助**（L-3）：与 P5 已排期的指定批次出库/批次级计费下钻（D-11 顺延项）合并设计，避免两次改 WA 端信息架构。
4. **上线前硬门禁**：检查单 1（Redis 密码+prod 冒烟）/5（shutdown 实测）/6（CVE 复扫）三项建议独立排一个上线准备任务，不混功能波。
5. **工程卫生**：L-6 mvnw 修复；`.e2e-tmp` 历史产物（P2/P3 期截图）可归档瘦身。
