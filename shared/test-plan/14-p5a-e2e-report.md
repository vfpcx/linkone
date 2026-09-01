# 14 · P5-A E2E 执行报告

> e2e-qa · 2026-09-01
> 用例规划：`shared/test-plan/14-p5a-e2e-cases.md`（v2，链 1/2/3 定稿）
> Spec：`frontend/apps/admin/e2e/p5a-announcement.spec.ts`（13 例，串行）+ `p5a-storefront-featured.spec.ts`（7 例，串行）
> 状态：🟢 **全量通过 20/20**（公告+消息中心 13 例；撮合运营 7 例）

---

## 1. 环境与前置

| 项 | 状态 |
|---|---|
| 后端 `:8080` | 重启至 main @ 4fc717b（B1 租户过滤修复 + b2cb572 撮合后端 V36）；Flyway V35/V36 已应用 |
| 前端 `:5173` | dev server 运行中，W4 6f0ca67 + B3 修复（LoginAnnouncementDialog route.path 补触发） |
| 造数 | `e2e/helpers/onboarding.ts`：真实注册 OPS/TA/WA；撮合链用 `seedActiveTenant` + WA 直申 + TA 审批 + `seedStockForWholesaler` + WA 确认入库 |
| 执行方式 | `npx playwright test p5a-announcement.spec.ts p5a-storefront-featured.spec.ts --workers=1`；typecheck `tsc -p e2e/tsconfig.json` 通过 |

## 2. 用例清单与结果（20/20 绿）

| 用例ID | 内容 | 结果 | 说明 |
|---|---|---|---|
| AN-01 | 发布 TA 目标公告 → TA 站内信（API+UI 铃铛） | ✅ 绿 | PUBLISHED + publishedAt；TA 未读≥1、公告分组含 title；角标可见 |
| AN-02 | 登录公告弹窗只弹一次（readAt 去重） | ✅ 绿 | **首次登录成功即弹**（B3 修复后直接断言）；确认后 readAt 非空；刷新重进不弹 |
| NC-01 | 消息中心分组 Tab（全部/业务/公告） | ✅ 绿 | 公告 Tab 含公告；业务 Tab 不含；全部 Tab 有数据 |
| NC-02 | 单条已读 + 幂等 | ✅ 绿 | `is-unread` → 标为已读 → 消除；再点行（展开）无错误提示 |
| NC-03 | 全部已读 → 角标清零（幂等） | ✅ 绿 | read-all 连续两次 0 错误；unread=0；角标隐藏 |
| AN-03 | 非目标角色 WA 收不到该公告 | ✅ 绿 | WA 公告分组为空；WA 登录无弹窗 |
| AN-04 | OPS 下架公告 → 状态 INACTIVE + 通知保留 | ✅ 绿 | INACTIVE + 中文「已下架」；TA 侧通知保留 |
| AN-05 | 重复发布 → 50502 且不翻倍 | ✅ 绿 | |
| AN-06 | DRAFT 直接下架 → 50502 | ✅ 绿 | |
| AN-07 | 创建缺 title/targetRoles → 40001 | ✅ 绿 | |
| AN-08 | title>128 / content>513 → 40001 | ✅ 绿 | |
| AN-09 | 非 OPS 访问公告管理 → 前端守卫 + 后端 42002 | ✅ 绿 | |
| NC-04 | 公告分组未读空态「暂无未读消息」 | ✅ 绿 | |
| FE-01 | 撮合配置 UI 全流程（空态 → 添加 → 保存 → 回显） | ✅ 绿 | 计数 1/20、1/5；保存后 GET `mainSkuIds/pinWaIds` 一致 |
| FE-02 | RT 店铺页主推/置顶标识 + 前置排序 | ✅ 绿 | API+UI 双断言：置顶排前、`pinned/featured` 标、商户内主推前置 |
| FE-03 | 主推 >20 / 置顶 >5 上限 | ✅ 绿 | 50711 / 50712 |
| FE-04 | 重复条目 | ✅ 绿 | 50713 |
| FE-05 | 非本店在售 SKU / 非本店批发商 | ✅ 绿 | 50714 |
| FE-06 | 覆盖写幂等 + 顺序更新 | ✅ 绿 | 同内容重复保存幂等；换序后 GET/店铺页顺序均更新 |
| FE-07 | 非 TA（WA）访问撮合配置 | ✅ 绿 | WA 审批后重登 → 42101；OPS 无租户 → 50210 |

## 3. 截图说明（test-results/screens/）

| 文件 | 内容 |
|---|---|
| `p5a-ta-dashboard-bell.png` | TA 工作台铃铛角标（未读公告） |
| `p5a-announcement-dialog.png` | 登录公告弹窗（首次登录即弹） |
| `p5a-notification-center.png` | 消息中心分组 Tab 实态 |
| `p5a-notification-empty.png` | 公告分组只看未读空态 |
| `p5a-ops-announcements.png` | OPS 公告管理页（含「已下架」中文态） |
| `p5a-featured-settings.png` | TA 店铺设置撮合运营卡（已选主推+置顶） |
| `p5a-rt-store-featured.png` | RT 店铺页主推/置顶标识 + 排序 |

## 4. 阻塞项（均已解决）

- ✅ **B1（W3 租户过滤）**：backend-dev 4fc717b 修复——`notifications` 移出租户行过滤，隔离边界改为 `recipient_user_id`（markRead 非本人 50341）。链 1/2 13/13 绿。
- ✅ **B2（撮合后端）**：backend-dev b2cb572 合入 V36 + `StorefrontFeatureController`（50711-50714）。链 3 7/7 绿。
- ✅ **B3（前端登录公告弹窗失效）**：e2e 实测发现（登录不弹、仅刷新才弹，watch 在 /login 路由早退）。frontend-dev 修复 `LoginAnnouncementDialog.vue`（route.path 离开认证页补触发 checkAndShow，单角色直跳/多角色切换器两路径覆盖）。AN-02 改为「首次登录即弹」直接断言并回归通过。

## 5. 变更与边界

- 新增文件（e2e 产物）：
  - `frontend/apps/admin/e2e/p5a-announcement.spec.ts`（13 例）
  - `frontend/apps/admin/e2e/p5a-storefront-featured.spec.ts`（7 例）
  - `shared/test-plan/14-p5a-e2e-cases.md`（v2）、`shared/test-plan/14-p5a-e2e-report.md`
- 未改动：backend/ 源码；frontend `src/`（B3 修复为 frontend-dev 的 `LoginAnnouncementDialog.vue` / `components.d.ts`，本报告不含）。
- 撮合链造数闭环：WA 直申（PENDING）→ TA 审批 → WK 入库（PENDING_WA_CONFIRM）→ WA 确认 → RT 进店 SKU 可见。

## 6. 全量回归（W5 收尾，e2e-qa 2026-09-01）

> 覆盖 `frontend/apps/admin/e2e/` 全部 19 个 spec（129 例，含 frontend-dev 视觉矩阵 8 例），串行 `--workers=1`。
> 前端 dev server 5173（main）+ 后端 8080（dev,local，终验重启至含 9104adf 的最新 main）运行中。

### 6.1 环境版本

| 项 | 值 |
|---|---|
| 后端 `:8080` | 首轮 @1f213f1；**终验重启至 main HEAD（含 9104adf 黑名单修复）** |
| 前端 `:5173` | dev server（main，热更新，含 9ee9eb7 41001 去重 + 27bad02 弹窗 375 适配） |
| Node / Playwright | v24.14.1 / 1.61.1 |
| 执行 | `npx playwright test --workers=1 --reporter=list`（fullyParallel:false, workers:1） |

### 6.2 全量 spec×结果（19 spec / 129 例）

| spec | 用例数 | 结果 |
|---|---|---|
| auth | 8 | ✅ 绿 |
| sell-flow | 5 | ✅ 绿 |
| sell-flow-2 | 5 | ✅ 绿 |
| onboarding-flow | 6 | ✅ 绿 |
| onboarding-visual | 8 | ✅ 绿 |
| inbound-dispute | 3 | ✅ 绿 |
| outbound-chain | 4 | ✅ 绿 |
| p3b-inbound-forward | 3 | ✅ 绿 |
| p3b-t3-returns-stocktake | 3 | ✅ 绿 |
| p3b-t4-batch-expiry | 3 | ✅ 绿 |
| p3b-w5-visual | 16 | ✅ 绿 |
| p4-billing | 5 | ✅ 绿 |
| p4-w5b-export-overview | 2 | ✅ 绿 |
| p4-w5-visual | 16 | ✅ 绿 |
| p5a-announcement | 13 | ✅ 绿 |
| p5a-storefront-featured | 7 | ✅ 绿 |
| p5a-w5-visual | 8 | ✅ 绿 |
| w5-visual | 14 | ✅ 绿 |
| **合计** | **129** | **129/129 绿** |

- 首轮（6bf99b9 前）121 例：119/121，2 例 onboarding-flow 缺陷见 6.3，修复后复跑全绿。
- 终验（HEAD 27bad02 + 后端 9104adf）：129 例全量 128 通过 + 1 例 **B-WA-04 seed 90001「系统繁忙」**（环境瞬时错误，非被测流程缺陷），单独重跑 5/5 绿，终值 129/129。
- onboarding-flow 复验（去掉两处规避）：**6/6 绿，51.1s**，见 6.3 ①②。
- 总时长：全量 8.6–9.5 分钟/轮。

### 6.3 失败处置记录

首轮 2 例失败（均在 onboarding-flow），另有 1 例 frontend-dev 的视觉 WIP spec 首版失败，均已在最新 main 上闭环（后端 9104adf / 前端 9ee9eb7 修复 + e2e 复验）：

1. **ONB-E2E-02（OPS 拉黑→WA 申请被拒→OPS 移除→可申请）** — 偶发失败。根因（后端缺陷）：`BlacklistServiceImpl.summarizePhoneValue` 仅按 **ACTIVE** 行判断摘要 `PHONE_****{last4}` 是否被占用，但唯一约束 `uk_blacklist_type_value` 对 **REMOVED** 行同样生效；新手机号尾号与历史 REMOVED 行摘要撞车时插入抛 `DuplicateKeyException`→前端 50310（「加入黑名单」弹窗不关闭、列表行不出现）。**处置**：① e2e 临时 workaround `blacklistSafeWaPhone`（先查 ACTIVE+REMOVED 摘要尾号集合再选号）复跑绿；② backend-dev 9104adf 根因修复（REMOVED 摘要参与占位，同尾号走 hmac4 消歧，BLK-05 红→绿双证 + 全量 470 例绿）；③ **重启后端后移除 workaround 复验 6/6 绿（51.1s）→ workaround 已删除**。
2. **ONB-E2E-04（WE 凭码注册→禁用→被踢回登录页）** — 稳定失败。根因（前端缺陷）：`src/api/http.ts` AUTH 分支对 41001 直接 `ElMessageBox.alert` 且**未去重**，reload 触发多个并发 41001 时堆叠多个「请重新登录」弹窗，上层 overlay 拦截下层按钮点击。**处置**：① e2e 临时 workaround `force: true` 点击最上层「去登录」复跑绿；② frontend-dev 9ee9eb7 根因修复（模块级 `logoutAlertPending` + Promise 链去重，并发 41001 只弹一个）；③ **移除 workaround（恢复正常 click）复验 6/6 绿（51.1s）→ workaround 已删除**。
3. **p5a-w5-visual.spec.ts（frontend-dev 视觉矩阵）** — 首版 1 失败（登录公告弹窗断言 ann1 命中 ann3）。根因（spec 造数时序）：beforeAll 在「登录弹窗」用例前即发布公告 3 并下架，而登录弹窗取「最新未读公告通知」（`group=ANNOUNCE&unreadOnly=true&size=1`，下架不删通知），TA 最新未读为公告 3。**处置**：frontend-dev 9ee9eb7 修正时序（公告 3 发布/下架移入 OPS 列表用例）并加 `dismissAnnouncementDialogIfShown`；全量 129 例中 8/8 绿。
4. **B-WA-04（sell-flow-2，跨商户确认被拒 50286）** — 全量轮 1 次 seed 阶段失败 `90001 系统繁忙`（后端瞬时错误），非被测流程缺陷；单独重跑 5/5 绿，无代码变更。
