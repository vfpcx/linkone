# 15 · P5-C「OPS 平台运营控制台」需求拆解 v1

> 编写：Team Lead · 2026-09-02
> 依据：`19-p5c-dashboard-design.md`（P5-C 各角色 Dashboard，OPS 行列为「占位页·口径待拍板」）+ `00-roadmap.md` v2.7（后续排期 A）+ `02-user-stories.md`（OPS 各故事）
> 状态：口径已拍板（D-OPS-1~6 全采纳）→ 设计 `21-p5c-ops-console-design.md` → ✅ 已实现（2026-09-02）
> 定位：P5-C 收官子项——TA 工作台（19）已真实化，OPS `/ops/dashboard` 是**最后一个纯占位工作台**（PlaceholderDashboard.vue）。

---

## 1. 现状基线（代码实测 2026-09-02）

| 项 | 现状 | 来源 |
|---|---|---|
| OPS 登录默认页 | `defaultRouterFor` 落 `/ops/dashboard`（stores/auth.ts）| 前端 |
| `/ops/dashboard` 页面 | `views/PlaceholderDashboard.vue` 占位（「建设中」），无任何数据 | 前端 |
| OPS 菜单 | 每页各自硬编码，Announcements.vue 最全：运营控制台 / 租户审核 / 黑名单 / 公告管理 / 客诉仲裁（租户审核页漏公告项）| 前端 |
| OPS 真实页 | 4 个：TenantAudit / Blacklist / Arbitrations / Announcements | 前端 |
| 后端 OPS 端点 | `/ops/blacklist`（页/加/删）、`/ops/arbitrations`（列表/裁决，OUTBOUND_COMPLAINT）、`/ops/announcements`（CRUD/发布/下架）、`/admin/tenants`（OPS 租户列表，status 过滤）、`/admin/tenant/{id}/audit` | 后端 |
| 角色判定 | Service 层 `requireOpsRole`（authService.hasRole OPS）；OPS 无租户上下文 → 平台级查询天然不进 TenantLine | 后端 |

**结论**：OPS 控制台后端为零存量接口，全部新建；但每一路计数都能在已有列表端点/实体上收敛，无新表、无外部依赖。

---

## 2. 需求

### 2.1 目标
OPS 登录即见的「平台运营控制台」：一眼看清**平台规模 / 我的待办 / 今日动态**，待办与既有 4 个管理页形成角标跳转闭环（取代占位页）。

### 2.2 页面区块
1. **平台规模卡**（只读概览，不含明细跳转的也可配入口）
2. **待办队列卡**（数字 → 点击跳对应管理页 + 该页自带筛选态）
3. **今日动态卡**（平台今日新增业务量）

---

## 3. KPI 口径草案（数据源以代码实测为准）

| 区块 | 字段 | 口径 | 数据源域 | 可落性 |
|---|---|---|---|---|
| platform | `activeTenantCount` | tenants `status=ACTIVE` 计数（营业仓库数，自助+OPS 代建合计数）| tenant 域直连 mapper | ✅（admin/tenants 列表同口径）|
| platform | `wholesalerBindingCount` | wholesaler_applications `status=APPROVED` 计数（**绑定数**：一账号入驻 N 仓计 N，多仓 2026-09-01 决策后口径自然成立）| tenant 域直连 mapper | ✅ |
| platform | `activeBlacklistCount` | blacklist `status=ACTIVE` 计数 | tenant 域直连 mapper | ✅（需新增 count 出口，现仅 page）|
| pending | `pendingTenantAudits` | tenants `status=PENDING` 计数（租户审核队列）→ 跳 TenantAudit | tenant 域直连 | ✅ |
| pending | `pendingComplaints` | arbitrations `bizType=OUTBOUND_COMPLAINT` 且 `status=PENDING` 计数（客诉仲裁队列）→ 跳 Arbitrations | document 域 **Service 出口**（照抄 countPendingForTa，G-S1/G-S2）| ✅（需新增 countPendingForOps）|
| pending | `draftAnnouncements` | announcements `status=DRAFT` 计数（草稿待发布）→ 跳 Announcements | notify 域 **Service 出口** | ✅（需新增 countDrafts）|
| today | `newTenantToday` | tenants `createdAt ≥ 今日 0 点` 计数（今日新入驻/新注册仓库）| tenant 域直连 | ✅ |
| today | `newComplaintsToday` | arbitrations（OUTBOUND_COMPLAINT）`createdAt ≥ 今日 0 点` 计数 | document 域 Service 出口 | ✅ |

> 口径说明：
> - **bill_disputes（账单申诉）不属 OPS 队列**——ST 处理（resolverUserId，D43 边界：与 arbitrations 两套实体互不落表），OPS 控制台不纳入；「争议账单平台仲裁」在 P5-D/D62 范围。
> - **capacity 快照 job 不进控制台**（US-TA-10 独立能力，挂待环境档）。
> - 「客诉」即 arbitrations 的 OUTBOUND_COMPLAINT（出库客诉，OPS 唯一仲裁入口）；入库异议仲裁归 TA 审批中心，不进 OPS 待办。

---

## 4. DECISION 清单（请拍板）

| # | 决策点 | 草案建议 | 默认 |
|---|---|---|---|
| D-OPS-1 | KPI 三块结构（平台规模/待办/今日动态）| 采纳三块 | ✅ 采纳 |
| D-OPS-2 | 平台规模字段集 | activeTenantCount + wholesalerBindingCount + activeBlacklistCount 三项 | ✅ 采纳 |
| D-OPS-3 | 待办字段集 | pendingTenantAudits + pendingComplaints + draftAnnouncements（均带页面跳转角标）| ✅ 采纳 |
| D-OPS-4 | 今日动态字段集 | newTenantToday + newComplaintsToday 两项（黑名单/公告今日新增价值低，不做）| ✅ 采纳 |
| D-OPS-5 | `wholesalerBindingCount` 口径 | 用 APPROVED 申请**绑定数**（非 wholesalers 主体账号数）| ✅ 采纳 |
| D-OPS-6 | 菜单补全 | 本轮顺带把 OPS 4 页菜单统一为 5 项（租户审核页补「公告管理」）| ✅ 采纳 |

> 若全部默认采纳，无需逐条讨论——回复「确认」即可进架构设计。

---

## 5. 契约草案

`GET /api/v1/ops/dashboard`，requireOps，无入参（OPS 无租户上下文，平台级）：

```json
{
  "platform": { "activeTenantCount": 0, "wholesalerBindingCount": 0, "activeBlacklistCount": 0 },
  "pending": { "pendingTenantAudits": 0, "pendingComplaints": 0, "draftAnnouncements": 0 },
  "today": { "newTenantToday": 0, "newComplaintsToday": 0 }
}
```

---

## 6. 后端改动清单（照 19 §4 模板，架构阶段细化）

1. **tenant 域**（聚合服务驻留域，同 TenantDashboardServiceImpl 先例）
   - 新建 `OpsDashboardService` + Impl + `OpsDashboardVo`（platform/pending/today 三层 Vo），gate=requireOps
   - `OpsDashboardController`（GET `/ops/dashboard`）
   - 域内直连：tenants 三计数 + wholesaler_applications APPROVED 计数 + blacklist ACTIVE 计数
2. **document 域**
   - `ArbitrationService.countPendingForOps()` + Impl（照抄 countPendingForTa，bizType=OUTBOUND_COMPLAINT 且 status=PENDING，无 tenant 维度）
3. **notify 域**
   - `AnnouncementService.countDrafts()` + Impl

## 7. 前端改动清单

- `views/ops/Dashboard.vue` 新建（或改 PlaceholderDashboard 为 ops 专用）：三块 KPI 卡 + 待办角标跳转；视觉沿用 OPS 端 shell（顶栏 + 左侧菜单），删除占位引用
- 路由 `/ops/dashboard` 指向新页
- OPS 4 页菜单统一补全（D-OPS-6）；`api/ops.ts` + `api-types/ops.ts` 增 `getDashboard()`

## 8. 测试

`OpsDashboardScenarioTest`（风格照 TenantDashboardScenarioTest，真实 HTTP + OPS 登录态）：
1. OPS-01 平台规模：自助注册 1 仓 + OPS 代建 1 仓 → activeTenantCount=2（ACTIVE）；REJECTED 不计
2. OPS-02 绑定数：WA 入驻 A 仓 + 同一账号入驻 B 仓 → wholesalerBindingCount=2（多仓绑定口径）
3. OPS-03 待办计数：PENDING 租户 1 → pendingTenantAudits=1；审核后归 0
4. OPS-04 客诉队列：WA 对出库单投诉 → pendingComplaints=1；OPS 裁决后归 0
5. OPS-05 黑名单/公告：加黑 ACTIVE 1 + 公告草稿 1 → 各自计数 1；移除黑名单/发布公告后归 0
6. OPS-06 今日动态跨日隔离：今日新仓 1 计入、昨日新仓不计入
7. OPS-S2 越权：TA/WA/ST/RW 登录 → 42001

## 9. 不做（本轮）

- OPS 其他数据看板（逐仓营收/批发商活跃/趋势图等运营 BI）——口径不在本期，控制台只做三块计数卡
- bill_disputes 平台仲裁（P5-D/D62）
- RT/ST Dashboard（已真实，19 §1）

## 10. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-02 | 首版：OPS 控制台口径草案 + D-OPS-1~6 待拍板 |
| v2 | 2026-09-02 | D-OPS-1~6 用户确认采纳；设计 21 定稿并实现（后端 7 用例 + 前端 Dashboard.vue + 菜单统一，回归 493 全绿） |
