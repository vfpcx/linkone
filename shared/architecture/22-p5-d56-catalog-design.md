# 22 · P5-D「D56 商品档案体系」架构设计 v1

> 状态：✅ 已实现 · 2026-09-02（口径 D-B-1~7 全采纳，product/16；实现见下；全量回归 500 全绿）
> 依据：`product/16-p5-d56-catalog.md` + US-OPS-02 + `03-database-schema.sql` §4.1 + product 域实测（V5 skus.spu_id 可空已留、spus/spec_types/spec_values 从未建表、业务全按 skus.id 关联）
> 变更记录：见文末

---

## 1. 范围（本档只承接 16 §2.3 核心）

1. **spus 平台级表落地**（无 tenant_id，不在 TenantLine 白名单，announcements 先例）+ `skus` 加 3 个标品快照列。
2. **OPS 标品库**：分页/搜索 + 新增（自动编码）+ 下架 + 合并（源→MERGED + 引用 SKU 原子重指 + 快照刷新）+ 品类字典端点。
3. **SKU 挂接**：TA/WA 建 SKU 可选挂 ACTIVE 标品（写快照）；编辑不改（16 §2.3）；`skus.spu_id` 可空语义保留。
4. 状态机 ACTIVE/OFFLINE/MERGED；MERGED 为终端态。

**后置**：spec_types/spec_values 规格字典、品类字典维护界面、条码、SPU 拆分、撮合改 SPU（16 §9）。

## 2. 领域与数据模型

### 2.1 归属
- `Spu` → **product 域**（与 Sku 同域，域名语义=商品档案；OPS 侧 Controller 照黑名单/公告先例路径 `/api/v1/ops/*`）。
- spus **平台级表**：无 tenant_id/wholesaler_id；不在 TenantLine 白名单 → OPS/公开只读不做行级隔离（与 announcements 同构，V35 先例）。

### 2.2 迁移 `V38__p5_d56_spu_catalog.sql`
```sql
CREATE TABLE spus (
  id BIGINT NOT NULL COMMENT '雪花ID',
  spu_code VARCHAR(32) NOT NULL COMMENT '平台编码（OPS 填/自动 GSPU-xxx，唯一）',
  name VARCHAR(128) NOT NULL COMMENT '标品名称',
  category_l1 VARCHAR(64) NOT NULL COMMENT '一级品类（预置字典文本）',
  category_l2 VARCHAR(64) NOT NULL COMMENT '二级品类',
  brand VARCHAR(64) NULL COMMENT '品牌（自由文本）',
  standard_image_url VARCHAR(512) NULL COMMENT '标准图',
  note VARCHAR(256) NULL COMMENT '备注',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/OFFLINE/MERGED',
  merged_to_spu_id BIGINT NULL COMMENT '合并源指向的新主标品',
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_spu_code (spu_code),
  KEY idx_spu_status_created (status, created_at)
) ...;
ALTER TABLE skus
  ADD COLUMN spu_name VARCHAR(128) NULL COMMENT '标品名称快照（挂接/合并时刷新）',
  ADD COLUMN spu_category_l1 VARCHAR(64) NULL,
  ADD COLUMN spu_category_l2 VARCHAR(64) NULL;
```
单文件（H2 MODE=MySQL 标准 SQL，V35 先例）；无 @TableLogic（MERGED/OFFLINE 已表达终态，不提供删除接口）。

### 2.3 冗余快照列（D-B-6）
`skus.spu_name/spu_category_l1/spu_category_l2` 在挂接与合并重指时整体刷新 → SKU 列表/店铺 VO 免 join；**以 OPS 标品为准，SKU 不覆盖**。

## 3. 契约

### 3.1 OPS（requireOps：Service 层 hasRole(OPS)，非 OPS → 42002，公告/黑名单先例）
| 方法 | 路径 | 入参 | 返回 |
|---|---|---|---|
| GET | `/api/v1/ops/spus` | keyword(名/编码模糊) + categoryL1 + categoryL2 + status + page + size | `Page<SpuVo>` |
| POST | `/api/v1/ops/spus` | `SpuCreateDto` | `SpuVo`（含生成编码） |
| POST | `/api/v1/ops/spus/{id}/offline` | - | void（ACTIVE→OFFLINE） |
| POST | `/api/v1/ops/spus/{id}/merge` | `targetSpuId` | void（事务：源 MERGED + merged_to + skus 重指+快照） |
| GET | `/api/v1/ops/spu-categories` | - | `List<SpuCategoryGroupVo{l1,l2s}>` |

`SpuCreateDto`：`name`(必填≤128)/`categoryL1`(必填，预置字典校验)/`categoryL2`(必填)/`brand`≤64/`standardImageUrl`≤512/`note`≤256/`spuCode`(可选≤32，唯一；空自动 `GSPU-<雪花短码>`，前缀同普通资源 id 规则)

`SpuVo`：`id/spuCode/name/categoryL1/categoryL2/brand/standardImageUrl/note/status/mergedToSpuId/referencedSkuCount/createdAt/createdBy`

### 3.2 SKU 挂接（S4 既有鉴权不变）
`SkuCreateDto.spuId` 非空 → 校验 SPU 存在且 ACTIVE（SPU_NOT_FOUND/SPU_NOT_LINKABLE），写快照 3 列。SkuVo 透出 `spuId/spuName/spuCategoryL1/spuCategoryL2`。列表接口（listByWholesaler/listByTenantForRt）零改动——实体直接带出快照。

### 3.3 状态机与合并（§ 见 16 D-B-4/5）
- ACTIVE→OFFLINE（下架）；ACTIVE→MERGED（合并源，事务内重指）；OFFLINE/MERGED 均不可合并/下架。
- 合并 target 须 ACTIVE 且非自身（SPU_MERGE_TARGET_INVALID）。
- 合并源 MERGED 后：`skus.spu_id=源` 全部重指 target 并刷新快照（单条 UPDATE，原子）；源标品页面仅展示（MERGED + mergedToSpuId），无操作钮。
- OFFLINE：存量 SKU 引用保留（店铺照卖），仅禁新挂接。

### 3.4 品类字典（D-B-2/7）
预置 seed 常量（product 域 `SpuCatalog` 静态 Map），`/ops/spu-categories` 出 `[{l1,l2s[]}]`；OPS 新增下拉两级联动 + TA 建 SKU「选标品」搜索不依赖字典（只读 ACTIVE 列表）。

## 4. 错误码（5072x，product 域，16 §4）
`SPU_NOT_FOUND 50720 / SPU_NAME_REQUIRED 50721 / SPU_STATE_INVALID 50722 / SPU_CATEGORY_INVALID 50723 / SPU_CODE_DUPLICATED 50724 / SPU_MERGE_TARGET_INVALID 50725 / SPU_NOT_LINKABLE 50726`

## 5. 后端清单（product 域）
- `entity/Spu`、`mapper/SpuMapper`（含批量引用计数 `@Select <script>`）、`vo/SpuVo`、`vo/SpuCategoryGroupVo`
- `dto/SpuCreateDto`、`service/SpuService`+`impl/SpuServiceImpl`（requireOps / 状态机 / 合并事务 / 编码生成与唯一）
- `controller/OpsSpuController`（/api/v1/ops/spus + /spu-categories）
- `catalog/SpuCatalog` 静态字典 + `SpuCodeGenerator`
- `Sku`/`SkuVo`/`SkuServiceImpl.createSku`：快照列 + ACTIVE 校验（注入 SpuService 只读出口，域内合法）
- `ErrorCode` 增 50720-50726

> 幂等/并发：合并与下架在 Service 事务内 read-check-write（状态校验在 update 前）；无并发热点（OPS 低频操作），不加乐观锁（公告/黑名单同构先例）。

## 6. 前端清单
- OPS `views/ops/SpuCatalog.vue`：搜索（keyword + 品类两级 + status）+ 表格（编码/名称/品类/品牌/状态/引用数/时间/操作：下架·合并）+ 新增弹窗（品类两级联动）+ 合并弹窗（选 ACTIVE 目标）+ MERGED 只读展示
- 菜单统一 5→6：运营控制台/租户审核/黑名单/公告管理/**标品库**/客诉仲裁（Dashboard/TenantAudit/Blacklist/Announcements/Arbitrations 五页同步）
- api：`api/ops.ts` + `api-types/ops.ts`（Spu/SpuQuery/SpuCreateRequest/SpuCategory）；TA Skus.vue 建 SKU「选择标品」+ 列表加标品列

## 7. 测试矩阵（OpsSpuScenarioTest，HTTP 主链 + mapper seed 混合；非全局聚合无需基线差分，但 OPS 新 SPU 每用例独立命名互不干扰）
1. SPU-01 新增 ACTIVE + 自动编码 + 品类非法 50723 + 编码重复 50724 + 非 OPS 42002
2. SPU-02 合并 A→B：seed 双 SKU 挂 A → merge → A=MERGED/merged_to=B、双 SKU 重指 B 且快照刷新、A 再操作 50722、target=OFFLINE/自身 50725
3. SPU-03 下架 OFFLINE：TA 挂 OFFLINE 标品 50726、存量 SKU 引用保留（/listed 正常含快照）
4. SPU-04 挂接成功：TA 建 SKU 带 ACTIVE spuId → sku 带快照字段
5. SPU-05 关键字/品类过滤分页 + referencedSkuCount 正确

## 8. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-02 | 首版：数据/契约/状态机/错误码/前后端清单/测试矩阵（16 D-B-1~7 拍板后） |
| v2 | 2026-09-02 | 已实现：V38 迁移（spus 平台级表 + skus 标品快照列）；product 域 `Spu`/`SpuServiceImpl`（requireOps 42002、ACTIVE/OFFLINE/MERGED 状态机、合并源 MERGED + 引用 SKU 单 SQL 原子重指 + 快照刷新、批量引用计数）/`OpsSpuController`/`CatalogSpuController`（登录态只读目录 /catalog/spus，补 TA 选标品越权盲点）；`SpuCatalog` 预置两级品类字典 + `/ops/spus/spu-categories`；`SkuServiceImpl` 挂接 ACTIVE 校验 + 快照列写；`OpsSpuScenarioTest` 7 例全绿；前端 `views/ops/SpuCatalog.vue`（新增弹窗两级联动/合并/下架/过滤）+ OPS 菜单 5→6 项统一 + TA Skus.vue 选标品与标品列；typecheck 通过；回归 500 全绿 |
