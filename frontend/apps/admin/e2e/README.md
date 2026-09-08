# 仓储云 · Playwright E2E 套件（双工程）

`@playwright/test`（TypeScript）端到端测试，覆盖账号域 8 条核心链路（E1-E8）、
phase-1 卖货整链（SELL/B-x）、P2 入驻生态 4 链路（ONB-E2E-01~04）、P3/P3b 单据链路、
P4 计费、P5-A 公告/撮合与视觉验收截图。
迁移自历史临时脚本 `.e2e-tmp/smoke.py` + `.e2e-tmp/extra.py`，逐条对齐选择器与断言。

## 双工程结构（F7-6 起，admin RT 过渡态退役前置）

一个 `playwright.config.ts` 同时跑两个 project，职责划分：

| project | baseURL | 覆盖 | 内容 |
| --- | --- | --- | --- |
| `chromium` | `http://localhost:5173`（admin dev）| `e2e/*.spec.ts`（`rt-h5/` 除外）| admin 端 UI（OPS/TA/WA/WK/ST 电脑端）+ **纯 API 契约断言**（RT 进店页/库存/撮合契约留在 admin 侧）|
| `uni-rt` | `http://localhost:5175`（uni dev，**hash 路由**）| `e2e/rt-h5/*.spec.ts` | RT 买家正式端（uni H5）UI 基线——admin 内 RT 最小 H5 过渡态退役后，买家 UI（进店/下单/卖光空态/空店/撮合展示/截图）由本工程承接 |

> 迁移口径：**「UI 可观测旅程」从 admin 迁到 uni-rt**；admin 只保留 API 契约断言
> （`fetchRtStore` + `stockOfSku` / 撮合聚合契约）。避免双端重复造数——
> `rt-h5/` 用例直接复用 `e2e/helpers/sell.ts` / `helpers/onboarding.ts` 的造数函数
> （`seedSellChain` / `seedEmptyStore` / `seedActiveTenant` / `registerWaWithTarget` 等），不复制逻辑。

## 前置条件（必须，外部启动）

测试**不会**自动拉起服务（`playwright.config.ts` 未配置 `webServer`），需先手动起好：

| 服务 | 地址 | 启动 |
| --- | --- | --- |
| admin dev server | http://localhost:5173 | `pnpm --filter @cangchu/admin dev` |
| **uni H5 dev server** | http://localhost:5175 | `pnpm --filter @cangchu/uni dev:h5` |
| 后端 API | http://localhost:8080 | 后端工程（依赖 **MySQL** + **Redis**） |

约定：

- mock 短信验证码固定 `888888`
- 后端契约：注册/登录返回 `roles` + `primaryRouter=/ta/dashboard`
- RT 买家正式端为 uni H5 hash 路由：进店 `/#/pages/rt/store/index?code=<店铺码>`

可用环境变量覆盖地址：`E2E_BASE_URL`（admin）、`E2E_UNI_URL`（uni）、`E2E_API_URL`（后端）。

## 安装

```bash
# 在 frontend/ 根目录
pnpm install
# 首次需下载 chromium（若本机已有可跳过）
pnpm --filter @cangchu/admin exec playwright install chromium
```

## 运行

```bash
# headless 全量（两个 project 依 config 顺序串行：chromium → uni-rt）
pnpm --filter @cangchu/admin e2e

# 只跑 RT 正式端（uni H5）工程
pnpm --filter @cangchu/admin e2e --project=uni-rt

# 交互式 UI 模式（调试）
pnpm --filter @cangchu/admin e2e:ui
```

报告：`reporter=list + html`，HTML 报告产物在 `playwright-report/`，
失败截图 / trace 在 `test-results/`（`screenshot=only-on-failure`，`trace=on-first-retry`）。
`uni-rt` 视觉用例截图产物在 `test-results/screens/`（不入库）。

## 用例清单

### admin 端（project `chromium`，5173）

| ID | 分组 | 场景 |
| --- | --- | --- |
| E1 | happy | TA 注册 → 直接进工作台 |
| E2 | happy | API 预置账号 → UI 密码登录 |
| E5 | happy | 找回密码两步重置（并验证新密码可登录） |
| E6 | happy | 工作台渲染校验（品牌/概览/菜单等区块） |
| E8 | happy | 退出登录 → 回登录页 |
| E3 | negative | 手机号格式错 → 字段报错 |
| E4 | negative | 密码错误 → 顶部告警 + 停留登录页 |
| E7 | idempotency S6 | 重复手机号注册 → 引导登录 |
| ONB-E2E-01 | onboarding-flow | WA 注册直申 → TA 审批 → WA 落 /wa/inquiry → TA 商户列表可见 |
| ONB-E2E-02 | onboarding-flow | OPS 拉黑 → 申请被拒(50205) → 移除后放行；OPS 路由角色守卫 |
| ONB-E2E-03 | onboarding-flow | 退驻前置自查 → 清库存 → TA 审批 → 踢出/隐藏/下架 → 60 天倒计时 → restore |
| ONB-E2E-04 | onboarding-flow | WA 生 WE 码(默认仅询价确认) → WE 凭码注册 → 禁用 → WE 被踢回登录页 |
| SELL-S1-02/S6 等 | sell-flow | 询价确认/出库链（**API 造数 + WA 电脑端确认 UI**；买家 UI 已迁 uni） |
| B-RT-03(API)/B-WA-04/B-EMP-02 等 | sell-flow-2 | 超量 50251 / WA 撤回 50284/50286 / 空商户 41303（纯 API 契约）|
| FE-02(API 段)/FE-06 等 | storefront-featured | 撮合配置管理 UI + RT 聚合 API 契约（店铺渲染迁 uni-rt）|
| P3/P3b/P4/P5-A 其余 | 各 spec | 单据/批次/退货/账单/公告（admin 电脑端）|
| V-* | w5-visual | 各期页面视觉验收截图（RT 店铺页截图已迁 uni-rt，见下）|

### RT 正式端 uni H5（project `uni-rt`，5175 hash 路由，移动视口）

| ID | 文件 | 场景 |
| --- | --- | --- |
| UNI-RT-S1-01 | rt-buy | 进店 → 步进 +3 → 手机号收集 → 成功弹层含 XJ- 单号 |
| UNI-RT-S2-01 | rt-buy | 非法手机号被拦截（弹层不关闭、不下单）|
| UNI-RT-S2-01b | rt-buy | 未选数量时无提交栏（无法下单）|
| UNI-RT-B02 | rt-journeys | 卖光后进店：无 SKU、空态提示、不报错 |
| UNI-RT-B03 | rt-journeys | 步进钳制：上限=库存，+ 到顶后按钮 `step--off` 不可再增 |
| UNI-RT-B07 | rt-journeys | 空店进店：空态提示、不报错 |
| UNI-RT-FE02 | rt-featured | RT 聚合 API 契约（主推/置顶/排序）|
| UNI-RT-FE02-UI | rt-featured | 正式端渲染：置顶商户前置 + 主推徽标 + tab 切换 |
| UNI-RT-VIS-01 | rt-visual | 进店 → 选量 → 提交成功弹层（375 截图）|

## uni H5 DOM 约定（rt-h5/ 用例选择器要点）

uni-app H5 编译产物与原生 Vue 有差异，写 `rt-h5/` 选择器时注意（详见 `rt-h5/rt-helpers.ts` 头注释）：

- `view/button/text` → 编译为 div/button 等原生元素，**class 原样保留**（`.store-head__name` / `.sku-row` / `.wa-tab` 等）
- **输入框是外层 + 内层原生 input**：`<input class="phone-input">` 编译为 `uni-input.phone-input > input`。
  断言值必须用 `.phone-input input`（`toHaveValue` 作用在原生 input 上）；直接 `.phone-input` 断言会失败。
  步进器同：`.step-input input`
- 弹层 = `.sheet`（手机号收集弹层 `.sheet:has(.phone-input)`，主按钮 `.btn-primary`；成功弹层 `.sheet__ok` + `.sheet__desc`，单号 `XJ-…` 从 desc 解析）
- 步进器到库存上限后按钮带 `.step--off`（`pointer-events:none`，B-RT-03 钳制）——`tapPlus` 遇 `step--off` 即停
- 店铺码进店：`/#/pages/rt/store/index?code=<code>`（hash 路由，URL 需 encodeURIComponent）

## 数据隔离

- 手机号用 `Date.now()` 时间戳生成，保证每次唯一（`helpers/api.ts#uniqPhone`）。
- 需要"已存在账号"的用例（E2/E5/E6/E8）通过后端接口旁路建号
  （`helpers/api.ts#seedTa`），不依赖 UI 注册，避免链路耦合。
- P2 起造数需注意：**RT 进店 / WA 入驻要求目标租户 ACTIVE**（F5 审查修复），
  `helpers/sell.ts#seedSellChain` 与 `helpers/onboarding.ts#seedActiveTenant`
  均已内置「临时 OPS 账号审核租户」步骤。
- `rt-h5/` 用例复用上述造数函数（对 admin 前端零依赖，直接打后端 API 造数），
  UI 动作全部在 uni H5 页面上完成。
