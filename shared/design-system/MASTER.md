# 仓储云 · 统一设计系统 MASTER（v1）

> 项目：仓储云（通用仓储 SaaS 平台）
> 版本：v1 · 2026-06-07
> 编写：Team Lead（基于 UI/UX Pro Max 推荐综合裁定）
> 依赖：PRD 全集 + 06-page-wireframes.md（51 ASCII 线框）
> 状态：草案 → 待前端 Agent 落地

## 📌 与产品线框图的关系

| 文档 | 回答的问题 | 怎么用 |
|---|---|---|
| **本文档 + `pages/*.md`** | 这个页面**怎么长**？ | **视觉规范**：token、组件、间距、动效 |
| **`shared/product/06-page-wireframes.md`** | 这个页面有**什么**？ | **信息架构**：51 个 ASCII 线框，字段、交互、流程 |

**前端开发约定**：
1. 先读 06-page-wireframes.md 找到目标页面线框 → 明确**有什么**
2. 再读本 MASTER.md → 用默认 token / 组件 落地视觉
3. 检查 `pages/{page-name}.md` 是否存在覆写 → 有则**覆写优先**

**覆写清单（覆写自 MASTER 默认的特殊页面）**：

| 06 线框章节 | design-system 覆写文件 |
|---|---|
| §0.5 登录 / 注册 / 找回密码 / 多角色切换器 / 安全设置 | [`pages/auth.md`](pages/auth.md) |
| §6 二批 / 终端（RT 店铺浏览 / 商品详情 / 询价） | [`pages/rt-store.md`](pages/rt-store.md) |
| §3.3 WK 入库扫码（含 §3.3b 代建入库的扫码部分） | [`pages/wk-scan.md`](pages/wk-scan.md) |
| §3.3 入库单 / §3.4b 代建出库的打印 / §3.4c 强制清库 | [`pages/print-templates.md`](pages/print-templates.md) |

其余 ~47 个页面**直接使用 MASTER 默认规范**，无需覆写文档。

---

## 0. 设计原则（先于任何决策）

仓储云是 **B 端为主、C 端简单浏览** 的多端 SaaS 平台。所有设计决策遵循：

1. **效率优先于美观** — 一线库管员要"3 秒看完单据、5 秒下决定"，不要营销级动效
2. **稳重专业，不要营销感** — 这是日常工具，不是促销 H5；不要紫色 AI 渐变、霓虹色、巨型字
3. **多端一致** — 同一个 SKU、单据、按钮在 PC / H5 / 小程序 视觉一致，仅按密度调整
4. **数据可信** — 金额、件数、托盘数等关键数字必须使用 **tabular nums**（等宽数字），不抖
5. **触屏友好** — H5 端最小点击 44×44pt，间距 8px+，不依赖 hover
6. **暗色模式延后** — MVP **不做暗色**，全平台仅亮色（试点上线后再迭代；架构上预留 token）

---

## 1. 四端定位与设计取舍

| 端 | 角色 | 设备 | 信息密度 | 设计模式 |
|---|---|---|---|---|
| **Admin 后台** | OPS / TA / ST | PC 优先 + H5 兜底 | 高 | Data-Dense Dashboard |
| **WK H5** | 库管员 | H5 优先 | 中 | Flat Mobile Touch-First |
| **WA H5** | 批发商管理员/员工 | H5 + PC 兜底 | 中-高 | B2B Mobile Operations |
| **RT 小程序** | 二批/终端 | 微信小程序 + H5 | 低 | E-commerce Clean |

### 1.1 拒绝的推荐（产品化裁定）

UI/UX Pro Max 给了部分不合适的模板，明确拒绝：

| 拒绝项 | 来源 | 理由 |
|---|---|---|
| WK 用 "Newsletter / Content First" 模式 | WK 推荐 | 库管员不需要订阅式落地页，需要单据队列 |
| RT 用 "Exaggerated Minimalism / Editorial" | RT 推荐 | 终端要看货、下询价，不需要时装杂志级排版 |
| WA 用 "Trust & Authority Healthcare" 风格 | WA 推荐 | 不需要医疗/金融级权威感，要轻便高效 |
| 所有"营销级动效"（巨型字、scroll-driven 视差） | 通用 | 工具属性，效率为王 |

---

## 2. 设计 Tokens（核心 · 跨端共用）

### 2.1 颜色 Token

仓储云统一使用 **「专业藏青 + 操作蓝 + 仓储绿」** 三色体系：

```css
/* ===== 品牌色 ===== */
--color-brand-primary:        #0F172A;  /* 藏青 - 顶部导航、深色背景、品牌强调 */
--color-brand-primary-on:     #FFFFFF;  /* 藏青上的文字 */
--color-brand-accent:         #0369A1;  /* 操作蓝 - 主 CTA、链接、选中态 */
--color-brand-accent-hover:   #0284C7;  /* 操作蓝 hover */
--color-brand-accent-on:      #FFFFFF;

/* ===== 状态色（语义色） ===== */
--color-success:              #059669;  /* 成功、已确认、在库 */
--color-success-bg:           #D1FAE5;
--color-warning:              #D97706;  /* 警告、临期、待审批 */
--color-warning-bg:           #FEF3C7;
--color-danger:               #DC2626;  /* 危险、过期、库存不足、异议 */
--color-danger-bg:            #FEE2E2;
--color-info:                 #0369A1;  /* 信息、提示 */
--color-info-bg:              #DBEAFE;

/* ===== 中性色（文字 + 背景 + 描边） ===== */
--color-fg-1:                 #020617;  /* 主文 - 标题、关键数字 */
--color-fg-2:                 #334155;  /* 次文 - 正文、表格内容 */
--color-fg-3:                 #64748B;  /* 弱文 - 说明、placeholder */
--color-fg-4:                 #94A3B8;  /* 极弱 - 禁用文字、辅助提示 */

--color-bg-1:                 #FFFFFF;  /* 卡片背景 */
--color-bg-2:                 #F8FAFC;  /* 页面背景 */
--color-bg-3:                 #F1F5F9;  /* 表头、二级容器 */
--color-bg-disabled:          #F1F5F9;

--color-border-1:             #E2E8F0;  /* 主分隔线 */
--color-border-2:             #CBD5E1;  /* 强分隔线、表单边框 */
--color-border-focus:         #0369A1;  /* 聚焦态 */

/* ===== RT 端专属调色（仅小程序/RT 浏览页使用） ===== */
--color-rt-accent:            #059669;  /* 生鲜绿（仅 RT 主 CTA / 询价按钮） */
--color-rt-accent-hover:      #047857;
--color-rt-warm:              #D97706;  /* 主推标识、热销标 */
```

**关键约束**：
- ✅ **RT 端在浏览/询价场景用「生鲜绿」**（让买家感受"新鲜、可购"），其余场景仍用藏青/蓝
- ❌ **绝不使用紫色、粉色、霓虹色、AI 渐变**
- ❌ 所有功能性颜色（红/绿/黄）必须配合 icon 或文字，不能只靠颜色传递信息（WCAG）

### 2.2 字体 Token

```css
/* 中文优先字体栈（PC + H5 + 小程序） */
--font-family-sans:
  'PingFang SC',                   /* iOS / macOS 中文 */
  'Microsoft YaHei',               /* Windows 中文 */
  'Helvetica Neue',
  Helvetica,
  'Hiragino Sans GB',
  Arial,
  sans-serif;

/* 数字字体（金额、件数、托盘数、单号 - 必用） */
--font-family-mono:
  'JetBrains Mono',
  'Fira Code',
  'SF Mono',
  Consolas,
  'Courier New',
  monospace;

/* 字号阶梯（4pt 网格） */
--font-size-display:   28px;  /* 仅工作台关键数字、空状态主图 */
--font-size-h1:        22px;  /* 页面主标题 */
--font-size-h2:        18px;  /* 区块标题、卡片头 */
--font-size-h3:        16px;  /* 小标题、强调段 */
--font-size-body:      14px;  /* 正文、表格 */
--font-size-body-lg:   16px;  /* H5 移动端正文（防 iOS 缩放） */
--font-size-caption:   12px;  /* 辅助说明、时间戳 */

/* 行高 */
--line-height-tight:   1.2;   /* 数字、单行标题 */
--line-height-normal:  1.5;   /* 正文 */
--line-height-loose:   1.75;  /* 长文档（极少） */

/* 字重 */
--font-weight-regular:  400;  /* 正文 */
--font-weight-medium:   500;  /* 标签、强调文字、表头 */
--font-weight-semibold: 600;  /* 卡片标题、按钮 */
--font-weight-bold:     700;  /* 关键数字、主标题 */
```

**关键约束**：
- ✅ H5 端正文 **必须 ≥ 16px**（防 iOS Safari 自动缩放）
- ✅ 所有金额、件数、托盘数、单号等数字用 `font-family: var(--font-family-mono)` + `font-variant-numeric: tabular-nums`
- ✅ 仅一个 Display 字号 28px，避免"巨型营销字"
- ❌ 不使用艺术字、衬线字（仓储工具不需要 Calistoga / Playfair 这类）

### 2.3 间距 Token（8pt 网格）

```css
--space-0:   0px;
--space-1:   4px;
--space-2:   8px;
--space-3:   12px;
--space-4:   16px;
--space-5:   20px;
--space-6:   24px;
--space-8:   32px;
--space-10:  40px;
--space-12:  48px;
--space-16:  64px;
```

| 场景 | 用法 |
|---|---|
| 表单字段间距 | `space-4`（16px）|
| 卡片内边距 | PC: `space-6`（24px）/ H5: `space-4`（16px）|
| 表格行高 | PC: `space-10`（40px）/ H5: `space-12`（48px，触屏友好）|
| 按钮内边距 | 横向 `space-4` / 纵向 `space-2` |
| 区块间距 | PC: `space-8`（32px）/ H5: `space-6`（24px）|
| 触屏热区 | 最小 44×44pt，不足时用 hitSlop 扩展 |

### 2.4 圆角 Token

```css
--radius-sm:    4px;   /* 标签、Tag、Badge */
--radius-base:  6px;   /* 按钮、输入框、小卡片 */
--radius-md:    8px;   /* 卡片、模态框 */
--radius-lg:    12px;  /* 大卡片、Toast、Drawer */
--radius-full:  9999px; /* 圆形头像、Pill */
```

> **不要圆角 ≥ 16px**（除非品牌设计，仓储云保持"专业、不卡通"）

### 2.5 阴影 Token

```css
--shadow-sm:   0 1px 2px 0 rgba(15, 23, 42, 0.04);
--shadow-base: 0 1px 3px 0 rgba(15, 23, 42, 0.08), 0 1px 2px -1px rgba(15, 23, 42, 0.04);
--shadow-md:   0 4px 6px -1px rgba(15, 23, 42, 0.08), 0 2px 4px -2px rgba(15, 23, 42, 0.04);
--shadow-lg:   0 10px 15px -3px rgba(15, 23, 42, 0.10), 0 4px 6px -4px rgba(15, 23, 42, 0.05);

/* 仅以下场景使用 */
/* shadow-sm: 按钮 hover / 表格行 hover */
/* shadow-base: 卡片默认 */
/* shadow-md: 弹窗、Drawer */
/* shadow-lg: 业务关键 Modal 与 弹层 */
```

**规则**：阴影只用 4 级，不使用 RGB 阴影、彩色阴影、霓虹光晕。

### 2.6 动画 Token

```css
--duration-fast:    150ms;  /* 按钮 hover/press、Toast 进入 */
--duration-base:    200ms;  /* 弹窗、Drawer 入场 */
--duration-slow:    300ms;  /* 页面切换、列表展开 */

--easing-standard:    cubic-bezier(0.2, 0, 0, 1);    /* 标准 */
--easing-decelerate:  cubic-bezier(0, 0, 0, 1);      /* 入场 */
--easing-accelerate:  cubic-bezier(0.3, 0, 1, 1);    /* 出场 */
```

**规则**：
- ✅ 所有动效 ≤ 300ms
- ✅ `prefers-reduced-motion: reduce` 时禁用所有非必要动画
- ❌ 不使用弹跳、晃动、光波等装饰性动效
- ❌ 不使用 scroll-driven 视差

### 2.7 Z-index 层级

```css
--z-base:      0;
--z-dropdown:  10;
--z-sticky:    20;     /* sticky 表头、固定底栏 */
--z-fixed:     30;     /* 顶部固定导航 */
--z-overlay:   40;     /* Drawer 背景遮罩 */
--z-drawer:    50;
--z-modal:     100;
--z-popover:   200;    /* Popover、Tooltip */
--z-toast:     1000;   /* 永远在最上 */
```

---

## 3. 组件库选型

### 3.1 PC 端（OPS / TA / ST · Vue 3）

| 项 | 选型 | 理由 |
|---|---|---|
| 组件库 | **Element Plus 2.x** | Vue 3 生态成熟、中文文档好、企业后台标配；体量小于 Ant Design Vue |
| 图标 | **Lucide Vue Next** | SVG，stroke 一致，可调粗细颜色；备用 Element Plus Icons |
| 图表 | **ECharts 5** + `vue-echarts` | 国内文档齐全，所有场景覆盖（线/柱/饼/桑基/地图）|
| 表格 | Element Plus `el-table` 基础 + `el-table-v2` 大数据虚拟滚动 | 流水/账单明细必用虚拟滚动 |
| 表单 | `el-form` + Zod / VeeValidate | 校验规则跟后端 errorCode 对齐 |
| 路由 | Vue Router 4 | — |
| 状态 | Pinia | 替代 Vuex |
| HTTP | Axios + 全局拦截器 | 统一错误码处理（按 05-error-codes.md 七大类）|
| 工具 | VueUse | `useDebounce` / `useLocalStorage` / `useEventListener` 等 |
| 地图 | **高德地图 JS API 2.0** | 与架构师 ADR-002 一致 |

### 3.2 H5 端（WK 库管 / WA 批发商 · uni-app）

| 项 | 选型 | 理由 |
|---|---|---|
| 框架 | **uni-app + Vue 3** | 一套代码编译 H5 + 小程序 + App，与后端约定一致 |
| 组件库 | **uni-ui 官方 + uView-plus 2.x** | uni-ui 兜底，uView-plus 补强（表单、Tabbar、Skeleton 等）|
| 图标 | Lucide SVG + uni-icons | uni-icons 兜底官方图标 |
| 图表 | **ucharts** | uni-app 生态最稳的轻量图表，覆盖 H5/小程序 |
| 状态 | Pinia | — |
| HTTP | uni.request + 全局拦截器 | 统一错误处理 |
| 工具 | uView-plus `u-utils` + 小型 utils | 防抖、节流、日期 |
| 地图 | 高德地图 uni 插件 / 微信小程序原生 map | — |
| 语音 | **uni.recorderManager + 阿里云 NLS SDK** | 与架构师 ADR-003 一致 |
| 扫码 | uni.scanCode | 库管员扫码入库 |

### 3.3 RT 小程序（二批/终端 · 微信小程序）

| 项 | 选型 | 理由 |
|---|---|---|
| 框架 | uni-app（与 WK/WA 共用代码库）+ 微信小程序原生兜底 | 节省人力 |
| 组件库 | uView-plus + 微信 weui-miniprogram 补充 | — |
| 路由 | uni-app pages.json | — |
| 风格 | 同设计 Token，但 CTA 用 `--color-rt-accent`（生鲜绿）| — |

### 3.4 共享内核（npm packages，monorepo）

```
@cangchu/design-tokens     # 本文档所有 token，导出 CSS Variables + Less/SCSS 变量
@cangchu/api-types         # 后端 OpenAPI → TS 类型（架构师建议）
@cangchu/error-codes       # 116 错误码 enum + i18n
@cangchu/ui-shared         # 共用业务组件（金额格式、状态徽章、容量进度条等）
```

---

## 4. 关键组件规范（跨端通用）

### 4.1 按钮（Button）

| 类型 | 用途 | 视觉 |
|---|---|---|
| Primary | 主 CTA，每屏 ≤ 1 个 | 藏青底白字 + 操作蓝 hover 边框 |
| Accent | 关键操作（提交、确认）| 操作蓝底白字 |
| Default | 次操作 | 白底蓝字蓝边 |
| Danger | 危险操作（删除、撤销） | 红底白字 |
| Ghost | 表格行内、轻量操作 | 透明 + 文字色 |
| Link | 文字链 | 操作蓝、无背景 |

**规则**：
- 高度：PC 32/40/48 三档；H5 默认 44px
- 圆角 `--radius-base`（6px）
- 加载态：按钮 disable + 内部 spinner，文字保留可读
- 危险操作 + 长按 200ms 才触发（防误触，PC 端可省略）

### 4.2 表格（Table）

PC 通用样式：

| 项 | 值 |
|---|---|
| 表头高度 | 44px |
| 行高 | 40px |
| 表头背景 | `--color-bg-3` |
| 表头字色 | `--color-fg-2` + 字重 600 |
| 单元格内边距 | 12px 16px |
| 行 hover | `--color-bg-2` |
| 行选中 | `--color-info-bg` + 左侧 3px 蓝竖线 |
| 排序图标 | Lucide `ArrowUpDown` / `ArrowUp` / `ArrowDown` |
| 操作列 | 始终最右；最多 3 个常用按钮 + 「更多」下拉 |
| 数字列 | 右对齐 + `tabular-nums` |
| 状态列 | Badge 组件 |
| 空状态 | 居中 `EmptyState`，含说明 + 操作建议 |
| 大数据 | ≥ 50 行强制虚拟滚动（el-table-v2 / ucharts） |

### 4.3 表单（Form）

```
[label *]  [Input............................]
                                              [helper 提示文字]
[label *]  [Select ⌄]
                                              [! error 提示在字段下方]
```

- ✅ 标签**总是可见**，不用 placeholder 替代 label
- ✅ 必填字段红色星号
- ✅ 校验在 blur 时触发，不在 keystroke 时
- ✅ 错误信息显示在字段下方（不在顶部聚集）
- ✅ 多错误时表单顶部加 ErrorSummary + 锚点定位到字段
- ✅ submit 后**自动 focus 第一个错误字段**
- ✅ 长表单（≥ 8 字段）自动保存 draft 到 localStorage

### 4.4 模态框（Modal / Dialog）

| 场景 | 形态 |
|---|---|
| 确认对话框（删除、撤销、危险操作） | 居中 Dialog 480px |
| 表单编辑（< 6 字段） | 居中 Dialog 600px |
| 表单编辑（≥ 6 字段或多 step） | Drawer 右侧滑出 480-720px |
| 详情查看 | Drawer 右侧滑出 600px |
| 复杂业务（如代建入库二次确认 + 大额校验） | 中间 Modal 含金额/件数高亮 |

**关键**：
- Modal 出现时 body 滚动锁定 + 背景遮罩 `rgba(15,23,42,0.5)`
- ESC 可关闭（仅允许的场景）
- 危险操作 Modal 主按钮**靠右**，红色，二次确认文案要明确（"删除 5 个 SKU 的客户专属价，不可撤销"）

### 4.5 反馈（Toast / Message / Notification）

| 类型 | 用途 | 显示时长 |
|---|---|---|
| Toast (Message) | 操作结果提示（已保存、提交成功） | 3s |
| Notification | 业务通知（新询价、待审批） | 5s + 可手动关闭 |
| Loading | 提交中、加载中 | 直到 promise 完成 |
| Skeleton | 列表/详情加载 > 300ms 时显示 | 直到数据到达 |

**关键**：
- Toast 不抢焦点（accessibility）
- Toast 包含 icon + 文字，不只靠颜色
- 错误 Toast 必须说明**原因 + 怎么办**（不仅"提交失败"，要"账单已下发不可冲销，请先撤回下发"）

### 4.6 状态徽章（Status Badge）

```
[● 待审核] [● 已通过] [● 已下架] [● 争议中] [● 已结清]
```

| 业务状态 | 配色 |
|---|---|
| 默认 / 草稿 | `bg-gray-100` + `text-gray-700` |
| 进行中 / 待办 | `bg-blue-50` + `text-blue-700` |
| 成功 / 已确认 | `bg-green-50` + `text-green-700` |
| 警告 / 待审批 | `bg-amber-50` + `text-amber-700` |
| 危险 / 异议 | `bg-red-50` + `text-red-700` |
| 已结束 / 归档 | `bg-slate-200` + `text-slate-600` |

每个 Badge 左侧带 `●` 圆点，**色 + 圆点 + 文字** 三冗余，符合 WCAG。

### 4.7 容量进度条（Capacity Bar）— 业务特色组件

```
件数  ▓▓▓▓▓▓▓▓░░░░ 72%  剩余 5,700 件
托盘  ▓▓▓▓▓▓░░░░░ 65%   剩余 42 托盘
```

- 利用率 < 70%：绿色填充
- 70% ≤ 利用率 < 90%：黄色填充
- 利用率 ≥ 90%：红色填充
- 数字 tabular-nums，右对齐
- 悬停显示精确数（精确档）或档位描述（模糊档）

### 4.8 金额组件（MoneyDisplay）— 业务特色组件

```html
<MoneyDisplay value={2340.56} />
<!-- 渲染为 -->
<span class="money">¥<span class="money-int">2,340</span>.<span class="money-dec">56</span></span>
```

- 整数部分加粗（700）
- 小数部分弱化（400 + 0.85em）
- 千分位 `,`
- 全部 tabular-nums
- 负数（冲销条目）显示为红色 + 前置 `-`

### 4.9 价格徽章（PriceBadge）— 业务特色组件

```
¥120         <!-- 公开价 -->
¥110 专属    <!-- 客户专属价，附绿色"专属优惠"标 -->
```

- 公开价：默认色，无标签
- 专属价：横放绿色小 Badge "专属优惠"，金额左侧带 ✓ 图标

### 4.10 状态时间线（Timeline）

用于单据流转轨迹：
```
○ 已提交  2026-05-29 14:30
│ 张三（WK）
○ 已受理  2026-05-29 14:32
│ 李四（WK）
● 已入库  2026-05-29 15:10   ← 当前状态
│ 李四（WK）
○ 待审批  ─                  ← 未来步骤虚化
```

- 当前节点实心 + 主色；已完成空心 + 主色；未来虚线 + `--color-fg-4`
- 节点间距 24px
- 每节点含时间 + 操作人

---

## 5. 数据可视化规范（ECharts / ucharts）

### 5.1 默认配色顺序

```
1. #0369A1  操作蓝     - 主指标
2. #059669  仓储绿     - 次指标
3. #D97706  橙黄       - 第三类
4. #DC2626  红         - 异常/警告
5. #6366F1  靛蓝       - 第四类（极少用）
```

> **最多 5 条线**，超过则改桑基/堆叠柱

### 5.2 业务图表清单

| 场景 | 图表类型 | 数据 |
|---|---|---|
| 容量利用率（仓库工作台） | 仪表盘 Gauge | 实时百分比 |
| 入库 / 出库 趋势（月） | 折线图 | X=日期，Y=件数 |
| 临期占比 | 环形图 + 中心数字 | 临期/在库/总量 |
| 账单趋势（年） | 堆叠柱状图 | 应收/已收/未收 |
| 仓库地图分布（OPS） | 高德地图 + Marker | 全国/区域 |
| 距离推荐（RT 首页） | 列表 + 地图切换 | 距离升序 |

### 5.3 必须遵守

- ✅ 图例总是显示（不折叠到滚动外）
- ✅ Tooltip 悬停显示精确数字
- ✅ X/Y 轴必有单位（"件" "元" "%"）
- ✅ 空数据状态用 `EmptyState` + 引导文案，不显示空轴
- ✅ 加载时显示 Skeleton 占位，不显示空白
- ✅ 移动端 < 6 数据点的趋势图退化为 KPI 卡片
- ❌ 不用 3D 饼图、3D 柱图
- ❌ 不用渐变填充（除非有语义，如温度图）

---

## 6. 响应式断点

```css
/* PC 优先：1440 / 1024 / 768 / 375 */
@media (max-width: 1440px) {}    /* xl: 笔记本默认 */
@media (max-width: 1024px) {}    /* lg: 平板横屏 / 小桌面 */
@media (max-width: 768px)  {}    /* md: 平板竖屏 */
@media (max-width: 375px)  {}    /* sm: 小屏手机 */
```

### 关键页面适配规则

| 页面 | 1440 | 1024 | 768 | 375 |
|---|---|---|---|---|
| Admin 工作台 | 4 列 KPI | 3 列 | 2 列 | 1 列（堆叠）|
| Admin 表格 | 完整 | 隐藏次要列 | 横滑或折叠 | 卡片化 |
| WK H5 | — | — | — | 原生设计 |
| WA H5 | 2 列卡片（PC 兜底）| 1 列 | 1 列 | 1 列 |
| RT 小程序 | — | — | — | 原生设计 |

---

## 7. 可访问性最低要求

- ✅ 主文 `--color-fg-1` vs `--color-bg-1`: 16.8:1 （AAA）
- ✅ 次文 `--color-fg-3` vs `--color-bg-1`: 4.6:1 （AA）
- ✅ 操作蓝 `--color-brand-accent` vs 白底: 5.7:1 （AA）
- ✅ 所有 icon-only 按钮带 `aria-label`
- ✅ 所有图表带可访问的数据表 fallback
- ✅ 表单 label 用 `<label for>` 而非 placeholder
- ✅ 错误用 `role="alert"` 或 `aria-live="polite"`
- ✅ 触屏热区 ≥ 44×44pt
- ✅ Tab 顺序与视觉顺序一致
- ✅ 支持 `prefers-reduced-motion`

---

## 8. 页面级覆写（Overrides）

某些页面需要偏离 MASTER 的特殊规则，记录在 `design-system/pages/{page-name}.md`：

| 页面 | 文件 | 原因 |
|---|---|---|
| RT 店铺浏览 | `pages/rt-store.md` | CTA 用生鲜绿，强调"新鲜可购" |
| 登录 / 注册 | `pages/auth.md` | 纯净大留白、品牌色压底 |
| WK 入库扫码 | `pages/wk-scan.md` | 全屏扫码相机视图，遮罩颜色 / 引导线特殊 |
| 单据打印模板 | `pages/print-templates.md` | 必须黑白可打印 + A4 / 80mm 热敏 适配 |
| Modal 大额校验 | `pages/large-amount-modal.md` | 件数 ≥ 50% 库存时红色警示 + 强制二次输入 |

（这些 override 文件由前端 Agent 在写对应页面时创建/更新）

---

## 9. 实施 Roadmap

| 阶段 | 产出 |
|---|---|
| Week 1 | `@cangchu/design-tokens` npm 包；Element Plus + uni-app 项目骨架；CSS variables 接入 |
| Week 2 | `@cangchu/ui-shared` 业务组件：MoneyDisplay / StatusBadge / CapacityBar / PriceBadge |
| Week 3 | Admin 工作台 + 登录 + 注册 + 找回密码 |
| Week 4 | WK H5 入库 / 出库 / 盘点；WA H5 首页 / 商品 / 询价 |
| Week 5 | RT 小程序首页 + 店铺页 + 商品详情 + 询价 |
| Week 6 | 联调、可访问性审查、性能压测 |

---

> **下一步**：基于本 MASTER 启动前端 Agent，按 Roadmap 落地代码。每个特殊页面在 `design-system/pages/` 下补 override 文档。
