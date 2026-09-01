# 14 · P5-A E2E 用例规划（通知中心增强 + 平台公告 + 撮合运营）

> UI 自动化测试 Agent · 2026-09-01
> 依据：`architecture/18-p5-design.md` v1（§4 接口 / §6 弹窗去重 / §7 前端改动 / §9 W4·W5 波次）+ `02-scenario-test-plan.md`（S1-S9 场景分类）
> 后端基线：main @ 4fc717b（W3 公告/通知中心 + B1 租户过滤修复 + B2 撮合后端 V36）
> 前端基线：6f0ca67（W4 页面）+ B3 修复（登录公告弹窗 route.path 补触发）
> 状态：🟢 全量通过 20/20（`p5a-announcement.spec.ts` 13 例 + `p5a-storefront-featured.spec.ts` 7 例）
> 执行结果明细见：shared/test-plan/14-p5a-e2e-report.md

---

## 1. 范围与依赖

| 模块 | 接口（已就绪） | 前端页面（已就绪） | E2E 状态 |
|---|---|---|---|
| 公告管理（OPS） | POST `/api/v1/ops/announcements`、GET 列表、POST `/{id}/publish`、POST `/{id}/inactivate` | OPS 公告管理页（列表/发布/下架） | ✅ 已通过 |
| 通知中心 | GET `/api/v1/notifications?group=`、POST `/read-all`、POST `/{id}/read`、GET `/unread-count` | 消息中心页（分组 Tab/只看未读/单条已读/全部已读）+ 登录公告弹窗 | ✅ 已通过 |
| 撮合运营（tenant） | GET/PUT `/api/v1/tenant/storefront/featured` | 店铺设置「撮合运营」区块 + 店铺页主推/置顶标 | ✅ 已通过 |
| storefront 出参 | `StoreFrontVo.featuredSkuIds/pinnedWholesalerIds/pinned/featured` | RT 店铺页主推"标"与置顶序 | ✅ 已通过 |

**已实测错误码**：50502 公告状态非法（重复发布/下架草稿）/ 40001 参数校验 / 42002 非 OPS / 50341 非本人消息按不存在（B1 修复回归）/ 42101 非 TA 租户权限 / 50210 无租户上下文 / 50711 主推超限 / 50712 置顶超限 / 50713 重复条目 / 50714 非法引用。

**弹窗去重语义（18 §6）**：复用 `notifications.readAt`——登录拉最新一条未读公告通知，确认/关闭即 markRead（幂等），天然"只弹一次"。

---

## 2. 测试数据与造数（复用既有 helpers，数据隔离）

- 造数走 `frontend/apps/admin/e2e/helpers/onboarding.ts`：`registerWithRetry`（TA/OPS/WA 各角色真实注册，SMS mock 888888）、`seedActiveTenant`（TA+OPS+租户审核）、`registerWaWithTarget` + TA 审批 + `seedStockForWholesaler` + WA 确认入库（撮合链进店 SKU 前置）。
- 全部用例用 `uniqPhone()` 唯一手机号，同一 spec 串行（`playwright.config.ts` `fullyParallel:false, workers:1`）。
- UI 进入：TA/WA 用 `loginAs`（真实登录动线，验证弹窗）；OPS 管理页用 `injectAuthAndGoto`（快速直达）。
- 公告创建标题带 `P5A` 前缀 + 唯一尾号，便于断言与清理识别。

---

## 3. 用例清单（全部已执行 ✅，20/20）

### 链 1 · 公告发布 → 站内信 → 弹窗一次（S1）

| 用例ID | spec 用例 | 场景 | 断言点（实现） |
|---|---|---|---|
| P5A-AN-01 | AN-01 | OPS 创建并发布 TA 目标公告 → TA 收到站内信 + 铃铛角标 | OPS 状态=PUBLISHED、publishedAt 非空；TA 未读≥1、公告分组含 title/refType=ANNOUNCEMENT；`.cc-topbar__bell .el-badge__content` 角标可见 |
| P5A-AN-02 | AN-02 | 登录公告弹窗只弹一次（readAt 去重） | 首次登录成功进入工作台即弹（B3 修复后）；弹窗含公告 title；点「知道了」→ API readAt 非空；刷新/重进不再弹 |
| P5A-NC-01 | NC-01 | 消息中心：分组 Tab（全部/业务/公告） | 「公告」Tab 含该公告；「业务」Tab 不含公告；「全部」Tab 有数据；中文 tab 无英文码 |
| P5A-NC-02 | NC-02 | 单条已读（含幂等） | 只看未读 → 未读公告行 `is-unread` → 点「标为已读」→ `is-unread` 消失；再点行（展开）无错误提示 |
| P5A-NC-03 | NC-03 | 全部已读 → 角标清零（含幂等） | API `read-all` 连续两次成功；unread=0；角标隐藏 |
| P5A-AN-03 | AN-03 | 非目标角色收不到该公告 | WA 公告分组不含该公告；WA 登录无该公告弹窗 |
| P5A-AN-04 | AN-04 | OPS 下架公告 → 状态更新，已发通知保留 | 状态=INACTIVE；TA 消息中心该公告仍可见；OPS 列表显示「已下架」中文 |

### 链 2 · 边界/异常（S2/S5/S6/S4）

| 用例ID | spec 用例 | 场景 | 断言点（实现） |
|---|---|---|---|
| P5A-AN-05 | AN-05 | 重复发布已 PUBLISHED 公告 | 50502；未读数不翻倍 |
| P5A-AN-06 | AN-06 | 对 DRAFT 公告执行下架 | 50502 |
| P5A-AN-07 | AN-07 | 创建公告缺 title / targetRoles | 40001 |
| P5A-AN-08 | AN-08 | 公告 title 超 128 / content 超 512 | 40001 |
| P5A-AN-09 | AN-09 | 非 OPS（TA）访问公告管理页/接口 | 后端 42002；前端守卫弹回 `/ta/` + 提示「无权访问平台运营页面」 |
| P5A-NC-04 | NC-04 | 全部已读后「公告」分组未读空态 | 「只看未读」下空态文案「暂无未读消息」 |

### 链 3 · 撮合运营（S1/S2/S3/S6/S4）

| 用例ID | spec 用例 | 场景 | 断言点（实现） |
|---|---|---|---|
| P5A-FE-01 | FE-01 | 撮合配置 UI 全流程 | 空态「尚未设置主推商品/置顶批发商」→ el-select 添加主推 SKU + 置顶批发商 → 计数 1/20、1/5 → 保存成功 → GET 回显 `mainSkuIds/pinWaIds` 一致 |
| P5A-FE-02 | FE-02 | RT 店铺页主推/置顶标识 + 前置排序 | 置顶批发商排首位 + `pinned=true` + 「置顶」标；商户内主推 SKU 前置 + `featured=true` + 「主推」标；普通商户无置顶标 |
| P5A-FE-03 | FE-03 | 主推 >20 / 置顶 >5 上限 | 50711 / 50712 |
| P5A-FE-04 | FE-04 | 重复条目 | 50713 |
| P5A-FE-05 | FE-05 | 引用非本店在售 SKU / 非本店批发商 | 50714 |
| P5A-FE-06 | FE-06 | 覆盖写幂等 + 顺序更新 | 同内容重复保存成功且不回显漂移；换序保存后 GET 与店铺页顺序均更新 |
| P5A-FE-07 | FE-07 | 非 TA（WA）访问撮合配置 | 42101（WA 审批后重登）；OPS 无租户上下文 50210 |

> FE-05 原规划为"相同内容重复保存"，实际实现即 FE-06 幂等覆盖写（uk(store_id,kind,ref_id) 语义由覆盖写保证）。

---

## 4. 场景覆盖矩阵（对照 02 §4）

| 场景 | S1 | S2 | S3 | S4 | S5 | S6 | 说明 |
|---|---|---|---|---|---|---|---|
| 公告+消息中心 | AN-01/02/04、NC-01/02/03 | AN-07/08 | NC-04 | AN-03/09 | AN-05/06 | NC-03 | 越权/状态机/幂等全覆盖 |
| 撮合运营 | FE-01/02 | FE-05 | FE-03 | FE-07 | — | FE-06 | 校验码 50711-50714、42101 实测 |

---

## 5. 选择器约定（已对齐 frontend-dev 实际实现）

| 元素 | 实际选择器 |
|---|---|
| 铃铛角标 | `.cc-topbar__bell .el-badge__content`（按钮 `[data-test="open-messages"]`） |
| 登录公告弹窗 | `[data-test="login-announcement-dialog"]` / 确认 `[data-test="announcement-confirm"]` |
| 消息中心容器 | `[data-test="notification-list"]`；分组 Tab `getByRole('button', { name: '全部'/'业务'/'公告' })` |
| 消息行/单条已读 | `.cc-notif__item`（未读 class `is-unread`）/ `.cc-notif__read-one` / 只看未读 `.cc-notif__unread` |
| OPS 公告行/状态 | `.ann-table .el-table__row`，状态中文「已下架」 |
| 撮合区块 | `[data-test="featured-card"]` / `.featured-block`（主推/置顶）/ el-select `.featured-block__select` / 保存 `[data-test="featured-save"]` |
| RT 店铺页 | `.rt-wholesaler`（置顶 class `rt-wholesaler--pinned` + 标 `.rt-tag--pinned`）/ `.rt-sku`（主推标 `.rt-tag--featured`） |

---

## 6. 阻塞项（均已解决）

1. ✅ **前端 W4（frontend-dev）**：6f0ca67 合入，`/ta/messages`、`/ops/announcements`、`/ta/settings` 撮合卡、`/rt/store` 主推/置顶标全部就绪。
2. ✅ **W3 后端通知可见性 bug（B1）**：backend-dev 4fc717b 修复——notifications 移出租户过滤表，隔离边界改为 recipient_user_id；回归用例 AN-01/02、NC-01/02/03 均绿。
3. ✅ **撮合后端 W4（B2）**：backend-dev b2cb572 合入 V36 + StorefrontFeatureController，链 3 全绿。
4. ✅ **前端 B3（登录公告弹窗失效）**：e2e 实测发现（登录不弹、刷新才弹），frontend-dev 修复 LoginAnnouncementDialog.vue（route.path 离开认证页补触发 checkAndShow，单角色直跳/多角色切换器两路径覆盖），AN-02 已按「首次登录即弹」回归通过。
5. 弹窗"关闭但保留未读"不在本期范围（18 §10 待确认 3，本期按"关闭=已读"）。

---

## 7. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-01 | 首版：链 1/链 2 用例定稿（公告+消息中心），链 3 撮合占位 |
| v2 | 2026-09-01 | 全量执行完成 20/20：链 1/2 经 B1 修复后 13/13 绿；链 3 撮合 7/7 绿（FE-01~07）；记录 B3 发现与修复；选择器改为实际实现 |
