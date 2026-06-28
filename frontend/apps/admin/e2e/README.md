# 仓储云 admin · Playwright E2E 套件

`@playwright/test`（TypeScript）端到端测试，覆盖账号域 8 条核心链路（E1-E8）。
迁移自历史临时脚本 `.e2e-tmp/smoke.py` + `.e2e-tmp/extra.py`，逐条对齐选择器与断言。

## 前置条件（必须，外部启动）

测试**不会**自动拉起服务（`playwright.config.ts` 未配置 `webServer`），需先手动起好：

| 服务 | 地址 | 启动 |
| --- | --- | --- |
| 前端 dev server | http://localhost:5173 | `pnpm --filter @cangchu/admin dev` |
| 后端 API | http://localhost:8080 | 后端工程（依赖 **MySQL** + **Redis**） |

约定：

- mock 短信验证码固定 `888888`
- 后端契约：注册/登录返回 `roles` + `primaryRouter=/ta/dashboard`

可用环境变量覆盖地址：`E2E_BASE_URL`（前端）、`E2E_API_URL`（后端）。

## 安装

```bash
# 在 frontend/ 根目录
pnpm install
# 首次需下载 chromium（若本机已有可跳过）
pnpm --filter @cangchu/admin exec playwright install chromium
```

## 运行

```bash
# headless 全量
pnpm --filter @cangchu/admin e2e

# 交互式 UI 模式（调试）
pnpm --filter @cangchu/admin e2e:ui
```

报告：`reporter=list + html`，HTML 报告产物在 `playwright-report/`，
失败截图 / trace 在 `test-results/`（`screenshot=only-on-failure`，`trace=on-first-retry`）。

## 用例清单

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

## 数据隔离

- 手机号用 `Date.now()` 时间戳生成，保证每次唯一（`helpers/api.ts#uniqPhone`）。
- 需要"已存在账号"的用例（E2/E5/E6/E8）通过后端接口旁路建号
  （`helpers/api.ts#seedTa`），不依赖 UI 注册，避免链路耦合。
