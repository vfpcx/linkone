# 仓储云 SaaS · 前端 monorepo

> 项目：仓储云通用仓储 SaaS 平台 · 前端实现
> 版本：v0.1 · 2026-06-07
> 框架：pnpm workspace + Vue 3 + TypeScript

## 项目结构

```
frontend/
├─ packages/                     # 4 个共享 npm 包（内部）
│  ├─ design-tokens/             # @cangchu/design-tokens · CSS 变量 + TS 常量
│  ├─ api-types/                 # @cangchu/api-types · 后端接口 TS 类型
│  ├─ error-codes/               # @cangchu/error-codes · 116 错误码 enum + i18n
│  └─ ui-shared/                 # @cangchu/ui-shared · 4 个跨端业务组件
└─ apps/                         # 2 个应用
   ├─ admin/                     # @cangchu/admin · OPS / TA / ST · PC Vue 3 + Element Plus
   └─ uni/                       # @cangchu/uni   · WK / WA / RT · uni-app（H5 + 微信小程序）
```

## 端 / 角色映射

| 端 | 入口 | 角色 |
|---|---|---|
| admin | `apps/admin` | OPS（运维）/ TA（仓库老板）/ ST（结算员） |
| uni | `apps/uni` | WK（库管员）/ WA（批发商）/ RT（终端） |

## 启动

```bash
# 1. 在 frontend/ 安装依赖
pnpm install

# 2. 启动 admin（PC）
pnpm dev:admin
# → http://localhost:5173

# 3. 启动 uni-app H5
pnpm dev:uni
# → http://localhost:5174

# 4. uni-app 微信小程序
pnpm --filter @cangchu/uni dev:mp-weixin
# 用微信开发者工具打开 apps/uni/dist/dev/mp-weixin
```

## 依赖约定

- **Node 版本**：≥ 18.18
- **pnpm 版本**：≥ 9.0（workspace + 内部包 link 必备）
- **包管理**：仅 pnpm，禁止 npm/yarn

## 设计系统

所有视觉规范由 `@cangchu/design-tokens` 提供：
- CSS 变量（admin + uni 全局加载）：`packages/design-tokens/src/tokens.css`
- TypeScript 常量（图表/动态样式用）：`packages/design-tokens/src/tokens.ts`

参考文档：`shared/design-system/MASTER.md`（已纳入版本控制）

## API 类型

`@cangchu/api-types` 提供后端 7 个账号接口 + 8 个租户接口的请求/响应类型。
后续 OpenAPI 完善后可由后端导出自动生成。

## 错误码

`@cangchu/error-codes` 实现按数值段路由的统一处理：

| 段 | 类别 | 前端处理 |
|---|---|---|
| 41xxx | AUTH | 清 token 跳登录页 |
| 42xxx | PERMISSION | Toast「无权限」 |
| 43xxx | LIMIT | Toast + 显示 Retry-After 倒计时 |
| 44xxx / 40xxx | VALIDATION | 字段红边 + Toast |
| 45xxx / 50xxx | STATE | Toast 明确"状态不可达" |
| 46xxx / 60xxx | BUSINESS | Toast 友好提示 |
| 9xxxx | SYSTEM | Toast「系统繁忙，已上报」+ trace_id |

## 已实现（v0.1）

- ✅ **packages 4 个**：design-tokens / api-types / error-codes / ui-shared
- ✅ **admin（PC）已实现**：
  - 登录页 `Login.vue`（双 Tab + 锁定弹窗）
  - 注册页 `Register.vue`（角色感知，URL `?role=ta|wa|wk|st|we|rt`）
  - 找回密码 `ForgotPassword.vue`（两步走）
  - 多角色切换器 `WorkspaceSwitcher.vue`
  - TA 店铺工作台 `ta/Dashboard.vue`（基于 06 §2.1 线框 + mock 数据）
  - 占位 `PlaceholderDashboard.vue`、`NotFound.vue`
- ✅ **4 个跨端业务组件**（MoneyDisplay / StatusBadge / CapacityBar / PriceBadge）
- ✅ **Axios 全局拦截器**（错误码分段路由）
- ✅ **Pinia auth store** + token 持久化

## ⚠️ 未完成（前任 Agent 在中断前未交付）

- ❌ **apps/uni/ 整个 uni-app 子项目**（H5 + 微信小程序入口，目录都未创建）
  - 对应未交付：WK / WA / RT 的 H5 端登录页、首页、扫码、询价等
  - 未来 Agent 需从 0 创建 `apps/uni/`、配置 `pages.json`、`manifest.json`、`vite.config.ts`
- ❌ admin 端剩余 47 个页面：单据、价格管理、询价、账单、容量公示、入驻审批 ...
- ❌ 未跑 `pnpm install` 验证（本机未试装）

后续 Agent 接力时优先级建议：
1. 跑 `pnpm install` 验证 admin 能启动到 5173
2. 创建 uni-app 子项目 + 跑通 dev:uni
3. 继续 admin 剩余页面 + uni 主要页面（建议 admin 优先 5 个核心后台页 → uni 优先 WK 入库 + RT 店铺）

## 编码约定

- TypeScript strict mode
- 组件 `<script setup lang="ts">`
- 状态管理 Pinia
- 表单校验 Element Plus 内置 + Zod
- 工具库 VueUse + dayjs
- ❌ 禁用 lodash / moment / jquery

## 端口分配

| 应用 | 开发端口 |
|---|---|
| admin | 5173 |
| uni H5 | 5174 |
