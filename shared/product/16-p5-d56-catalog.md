# 16 · P5-D「D56 商品档案体系（OPS SPU 标品库）」需求拆解 v1

> 编写：Team Lead · 2026-09-02
> 依据：`02-user-stories.md` US-OPS-02 + `14-p5-requirements.md`（P5-D 概述 D56）+ `99-open-questions.md` L82（体系延后决策）+ `03-database-schema.sql` §4.1（spus 目标态）+ 代码实测（product 域现状）
> 状态：口径已拍板（D-B-1~7 全采纳）→ 架构 `22-p5-d56-catalog-design.md` → ✅ 已实现（2026-09-02）
> 定位：P5-D 起步子项（roadmap v2.7 排期 B）

---

## 1. 现状基线（代码实测 2026-09-02）

| 项 | 现状 | 影响 |
|---|---|---|
| `spus` / `spec_types` / `spec_values` | **从未建表**（纯 03-database-schema §4.1-4.3 文档规划），代码零引用 | D56 需新迁移建表 |
| `skus.spu_id` | V5 已建（BIGINT NULL，注释「phase-1 不强制平台 SPU」），SkuServiceImpl 直落、无校验 | 已有落点，可做非空约束管控 |
| SKU 字段 | name + spec(自由文本) + unitPrice/moqPrice/moqQty + mainImage + listed；**无 unit/category/code** | 品类靠 SPU 两级；历史 spec 保留 |
| 业务引用 | 库存/批次/单据/专属价/撮合全按 `skus.id`（per-wholesaler sku）| 挂 SPU 为加表增量，**不动**既有链路 |
| OPS 前端 | 5 页（Dashboard/TenantAudit/Blacklist/Arbitrations/Announcements），无标品库 stub | 需新建页面 + 菜单（5→6 项）|
| TA/WA 建 SKU | `POST /tenant/skus` 手填 name/spec/价，前端 ta/Skus.vue 镜像 | 可选挂 SPU = 入参 spuId 由「选标品」带出 |
| 撮合/RT | storefront MAIN_SKU ref=skus.id；inquiry_items.sku_id 直指 | 本期不动 |

---

## 2. 需求

### 2.1 目标
落地 **US-OPS-02 SPU 标品库**：OPS 维护平台级「标品」（两级品类 + 品牌 + 标准图 + 平台编码），全平台仓库/批发商 SKU 可挂接统一标品；SKU 建/改时「选标品」自动带出品类上下文。**撮合店/询价/专属价/库存仍按 skus.id，不改口径**。

### 2.2 术语
- **SPU（标品）**：平台统一商品档案（如「农夫山泉 550ml×24 瓶/箱」），全平台共享，与具体仓库/批发商无关。
- **SKU**：仓库内实际售卖项（现 skus 表，带 wholesaler_id），挂 `spu_id` 表示该售卖项属于某标品。历史自由 spec 文本保留不动。

### 2.3 本波范围（默认，见 D-B-1）
- **核心 = SPU 库生命周期 + SKU 挂接**：
  1. 数据迁移：建 `spus` 表（对齐 03 §4.1 精简）
  2. OPS「标品库」页：分页/搜索列表 + 新增 SPU（两级品类下拉 + 品牌 + 标准图 + 备注）+ 下架（OFFLINE）+ **合并**（A→B：A 置 MERGED + merged_to_spu_id=B + 全平台 `skus.spu_id=A` 原子重指 B）
  3. TA/WA 建 SKU 可选「选择标品」（搜索 ACTIVE 标品，回填 spu_id；编辑不变），SKU 列表展示所属标品
  4. 状态机：ACTIVE（默认，平台可见）→ OFFLINE（OPS 下架，不提供新挂接，存量 SKU 引用保留）；MERGED 仅合并源态
- **后置不做**（见 D-B-7）：`spec_types/spec_values` 规格字典、品类字典维护界面、条码/EAN、SPU 拆分（MERGE 反向）、撮合改按 SPU。

---

## 3. 品类体系草案（决策 D-B-2 用）

两级品类作为 **spus 表字段字符串**（schema 4.1 既有设计），不建 category 表。**预置 seed 字典**（代码常量 + 前端下拉同源），OPS 下拉两级联动，自由任选不做多级树维护。

| L1 | L2 示例 |
|---|---|
| 粮油调味 | 米面粮油 / 食用油 / 调味品 / 干货杂粮 |
| 酒水饮料 | 饮用水 / 碳酸/果汁 / 茶咖 / 啤酒白酒 |
| 休闲零食 | 饼干糕点 / 糖果巧克力 / 坚果炒货 / 膨化食品 |
| 方便速食 | 方便面 / 速冻食品 / 罐头 / 挂面粉丝 |
| 乳品冲调 | 牛奶 / 酸奶 / 奶粉 / 冲调饮品 |
| 日化清洁 | 洗衣液/皂 / 清洁剂 / 纸品 |
| 个护美妆 | 洗护发 / 沐浴 / 护肤 |
| 家居百货 | 厨房用品 / 收纳 / 一次性用品 |
| 其他 | 其他 |

> 品牌字段自由文本；L1/L2 用中文文本存储（对齐 schema VARCHAR(64) 与规则 8「零角色码」精神——商品名无码场景，品类直接中文）。

---

## 4. 契约草案

`GET /api/v1/ops/spus?keyword=&categoryL1=&categoryL2=&status=&page=&size=` — 分页列表（records/total）
`POST /api/v1/ops/spus` — 新增（body: name/categoryL1/categoryL2/brand/standardImage/note；**spuCode 可选，缺省平台自动生成**）
`POST /api/v1/ops/spus/{id}/offline` — 下架（ACTIVE→OFFLINE）
`POST /api/v1/ops/spus/{id}/merge` — 合并（body: targetSpuId；source→MERGED + skus 重指，source 不可再操作）
`GET /api/v1/ops/spu-categories` — 两级品类字典（OPS 页面与 TA 选标品共用；也可放常量，端点化便于未来字典维护）

OPS 越权：非 OPS → 42002（requireOps 先例）。

## 5. 后端改动清单（架构阶段细化）

- **product 域**（商品档案驻留域）
  - 新 entity `Spu` + mapper + `SpuService`/`SpuServiceImpl`/`OpsSpuController`（/ops/spus）
  - `SkuService`：创建/编辑入参 spuId 存在性校验（ACTIVE）+ 冗余 spuName/spuCategoryL1/L2 回填 skus 展示列（避免列表 N+1 join；若 skus 不加冗余则 VO join spus，架构定）
  - 合并事务：source status=MERGED + merged_to_spu_id + `UPDATE skus SET spu_id=target WHERE spu_id=source`（单 SQL 原子）
- **迁移**：`V{next}__init_spu_catalog.sql`（spus 表；不建 spec 表）
- 平台级表无 TenantLine（同黑名单/公告，OPS 维护 + TA 只读选择）

## 6. 前端改动清单

- OPS：`views/ops/SpuCatalog.vue`（列表 + 搜索 + 新增弹窗两级品类联动 + 合并确认 + 下架）+ 菜单统一（5→6 项：运营控制台/租户审核/黑名单/公告管理/标品库/客诉仲裁）
- TA：`ta/Skus.vue` 新增/编辑可选「选择标品」（搜索下拉 ACTIVE + 展示品类）；列表加「所属标品」列
- api/api-types：`/ops/spus*`、`/spu-categories`、`/tenant/skus` 入参补 spuId

## 7. DECISION 清单（请拍板）

| # | 决策点 | 草案建议 | 默认 |
|---|---|---|---|
| D-B-1 | 本波范围 | **核心**：SPU 生命周期(含合并重指) + SKU 可选挂 SPU + 两级品类（§2.3）；规格字典/条码/撮合改 SPU 全部后置 | ✅ |
| D-B-2 | 品类体系 | 两级中文文本存 spus 字段 + 预置 seed（§3 表）+ OPS 下拉两级联动；不做 category 表与维护界面 | ✅ |
| D-B-3 | spuCode | OPS 可填可选，空则自动生成唯一平台编码（如 `GSPU-<雪花短码>`）；spus.code 唯一索引 | ✅ |
| D-B-4 | 合并语义 | source→MERGED + merged_to_spu_id=target；引用 skus.spu_id 单 SQL 重指；历史（单据/库存）不动；source 不可下架/再合并/挂新 SKU | ✅ |
| D-B-5 | 下架语义 | OFFLINE 后存量 SKU 引用保留可用（店铺照卖），仅禁止新挂接；TA 列表仍展示 | ✅ |
| D-B-6 | SKU 冗余列 | skus 加 spu_name/spu_category_l1/l2 冗余（写时快照，列表免 join）；挂接后改 SPU 名称以 OPS 为准，SKU 不覆盖 | ✅ |
| D-B-7 | 品类端点 | `/ops/spu-categories` 出字典（前端下拉与选标品同源），便于未来升级维护 | ✅ |

> 全默认采纳即回复「确认」；品类 seed 清单（§3）可一并调整。

## 8. 测试要点（架构阶段细化）

1. SPU-01 OPS 新增 ACTIVE + 自动编码 + 品类回显（非 OPS 42002）
2. SPU-02 合并 A→B：A MERGED、双 SKU（原挂 A）重指 B、B 列表引用数正确、A 不可操作
3. SPU-03 下架：OFFLINE 不可新挂接；存量 SKU 店铺正常（sku 列表返回不变）
4. SPU-04 TA 建 SKU 挂 ACTIVE 标品成功 / 挂 OFFLINE/MERGED/不存在 → 业务码
5. SPU-05 关键字/品类过滤分页
6. S2 越权：TA 调 OPS 端点 42002；TA 选标品只读 ACTIVE

## 9. 不做（本轮）

- `spec_types/spec_values` 规格字典（后置子波，可并入后续 C 池；现有自由 spec 文本继续承载「规格摘要」）
- 条码/EAN/进销存打通、SPU 拆分、品类字典维护界面、撮合/RT 改按 SPU、SKU 历史强制回填

## 10. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-02 | 首版：D56 核心（SPU 库 + SKU 挂接 + 两级品类）范围/契约/决策 D-B-1~7 待拍板 |
| v2 | 2026-09-02 | D-B-1~7 用户确认全采纳；架构 22 定稿并实现（后端 7 用例 + 前端 SpuCatalog.vue + 菜单 6 项统一 + TA 选标品，回归 500 全绿） |
