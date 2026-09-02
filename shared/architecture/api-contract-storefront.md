# storefront 相关接口契约（撮合配置 + RT 进店浏览 · 权威 · 以实现为准）

> 项目：仓储云
> 版本：v1 · 2026-09-01
> 编写：架构师 Agent
> 依赖：04-api-spec.md（通用约定）/ 05-error-codes.md（错误码）/ 18-p5-design.md（设计口径 §2.2/§4.3/§4.4/§5/§6）
> 状态：**已对齐当前后端实现**（P5-A W4 合入，commit b2cb572；单一事实源 / Single Source of Truth）
> 归属说明：撮合配置接口（`/api/v1/tenant/storefront/featured`）物理归属 **tenant 域**（`StorefrontFeatureController`，`storefront_featured` 表唯一归属 tenant 域）；storefront 域**只读消费**（G-S2 Service 出口，禁跨域 mapper 直连）。本文档一并固化「配置端（TA 鉴权）」与「浏览端（RT 公开只读）」两侧契约。

---

## 0. 文档说明

本文档固化 **P5-A W4 撮合运营 + RT 进店浏览** 的请求/响应/错误码契约，**以后端实际代码为准**：

- **撮合配置端**（TA）：主推商品（≤20）/ 置顶批发商（≤5）的覆盖保存与回显，`storefront_featured` 表读写仅限 tenant 域。
- **浏览端**（RT）：进店页 / 批发商列表 / 商户 SKU 列表，P5-A W4 出参加入主推/置顶标记与前置排序。

权威来源（核对依据）：
- Controller：`backend/.../tenant/controller/StorefrontFeatureController.java`、`backend/.../storefront/controller/RtStoreController.java`
- Service：`backend/.../tenant/service/impl/StorefrontFeatureServiceImpl.java`、`backend/.../storefront/service/impl/StoreFrontServiceImpl.java`
- DTO/VO：`backend/.../tenant/dto/StorefrontFeatureSaveDto.java`、`tenant/vo/StorefrontFeatureVo.java`、`storefront/vo/StoreFrontVo.java`、`StoreSkuVo.java`、`StoreWholesalerVo.java`

---

## 1. 通用约定

- 统一响应包装 `R<T>`：`{ code, message, data }`，`code=0` 成功。
- **所有 id**（storeId / tenantId / wholesalerId / skuId / refId）后端用 `ToStringSerializer` 序列化为 **string**，前端按 string 处理。
- 时间字段为 `LocalDateTime`（无时区偏移）。
- **租户隔离**：撮合配置接口的 `tenantId` 一律取登录态推导的可信租户（`TenantContext`），**不接受客户端传入**；TA 未绑定租户 → `50210`。
- 鉴权：
  - 配置端 `/api/v1/tenant/storefront/featured/**`：需登录，且为当前租户 **ACTIVE TA**（Service 层 `hasRole(userId, "TA", tenantId)`，唯一可信来源为 `user_roles`）；越权 → `42101`。
  - 浏览端 `/api/v1/rt/**`：**公开只读**（不在 SaInterceptor include 列表，默认开放）；已登录 RT 携带有效 token 时下发专属价 `matchedPrice`，匿名仅公开价。

---

## 2. 撮合配置接口（TA，归属 tenant 域）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 1 | GET | `/api/v1/tenant/storefront/featured` | 回显当前店铺主推商品 / 置顶批发商 id（有序） |
| 2 | PUT | `/api/v1/tenant/storefront/featured` | 覆盖保存（先删后插，同事务；写前校验，幂等） |

### 2.1 回显 `GET /api/v1/tenant/storefront/featured`

响应：`R<StorefrontFeatureVo>`：

```json
{ "code": 0, "message": "ok", "data": { "mainSkuIds": ["1842...", "1842..."], "pinWaIds": ["1842..."] } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `mainSkuIds` | string[] | 主推商品 id 序（`KIND_MAIN_SKU`，按 `sort_order` 升序；无配置返回空数组） |
| `pinWaIds` | string[] | 置顶批发商 id 序（`KIND_PIN_WA`，按 `sort_order` 升序；无配置返回空数组） |

### 2.2 覆盖保存 `PUT /api/v1/tenant/storefront/featured`

```json
{ "mainSkuIds": ["1842...", "1842..."], "pinWaIds": ["1842..."] }
```

| 字段 | 必填 | 校验 | 说明 |
|---|---|---|---|
| `mainSkuIds` | 否（可空=清空） | ≤20 个（`50711`）；无重复（`50713`）；须为本租户**在售（listed=true）** SKU（`50714`） | 主推商品 id 有序数组；**数组顺序落 `sort_order`（0 起）** |
| `pinWaIds` | 否（可空=清空） | ≤5 个（`50712`）；无重复（`50713`）；须为本店入驻且 **ACTIVE** 批发商（`50714`） | 置顶批发商 id 有序数组；**数组顺序落 `sort_order`（0 起）** |

- **覆盖保存语义**：以本次列表为最终态——同事务 `DELETE (store_id, kind)` 后按数组顺序 `INSERT` 新行；PUT 天然幂等。
- 校验均**写前**执行（不改变任何数据即抛错）；`mainSkuIds`/`pinWaIds` 传 null 或缺省字段按清空处理。
- 在售 SKU 校验走 product 域 `SkuService.listByTenantForRt`（G-S2 服务出口，**禁 mapper 直连**）；库存为浏览期天然过滤，不阻断配置保存。
- 响应 `R<Void>`。

---

## 3. RT 进店浏览接口（`/api/v1/rt/**`，公开只读）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 1 | GET | `/api/v1/rt/store?storeId=&code=` | 进店页：店铺信息 + 店内 ACTIVE 批发商 + 各自在售 SKU |
| 2 | GET | `/api/v1/rt/wholesalers?storeId=&code=` | 店内批发商列表（仅 ACTIVE，不含 SKU） |
| 3 | GET | `/api/v1/rt/skus?storeId=&code=&wholesalerId=` | 某商户在售 SKU（含公开价 + 当前库存） |

Query 参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `storeId` | string(雪花) | 二选一（优先） | 店铺 id |
| `code` | string | 二选一 | 店铺码（= 租户简码 `tenantSimpleCode`） |
| `wholesalerId` | string(雪花) | #3 必填 | 批发商 id |

### 3.1 P5-A W4 出参扩展（18-p5-design §4.4）

**`StoreFrontVo` 新增：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `featuredSkuIds` | string[] | 主推商品 id 序（`MAIN_SKU`，按配置顺序） |
| `pinnedWholesalerIds` | string[] | 置顶批发商 id 序（`PIN_WA`，按配置顺序） |

**`StoreWholesalerVo` 新增：`pinned`**（boolean）——该批发商是否置顶（出现在 `pinnedWholesalerIds` 中）。

**`StoreSkuVo` 新增：`featured`**（boolean）——该 SKU 是否主推（出现在 `featuredSkuIds` 中）。

**排序语义（前后端一致）：**
- 店内批发商列表：置顶批发商**前置**（按配置顺序 `sort_order` 排列，未置顶保持原序）；无配置时行为与旧版一致。
- 商户 SKU 列表：主推 SKU **前置**（按配置顺序，未主推保持原序）。
- 前端可直接消费 `featured`/`pinned` 标记展示「主推」标，不必自行重排。

### 3.2 出参字段（浏览端）

`StoreFrontVo`：`storeId` / `tenantId`（string）、`storeCode` / `storeName` / `intro` / `coverUrl` / `businessHours` / `status`、`wholesalers`（`StoreWholesalerVo[]`）、`featuredSkuIds` / `pinnedWholesalerIds`（P5-A W4）。

`StoreWholesalerVo`：`wholesalerId`（string）、`name` / `intro` / `status`、`skus`（`StoreSkuVo[]`）、`pinned`（P5-A W4）。

`StoreSkuVo`：`skuId` / `wholesalerId`（string）、`name` / `spec` / `mainImage`、`unitPrice`（公开单价）、`moqPrice`（起批价）、`moqQty`（起批量）、`stockQty`（当前库存，>0 才出现在列表）、`matchedPrice`（客户专属价：仅已登录 RT 且命中有效专属价且 ≠ 公开单价时有值，否则 null）、`featured`（P5-A W4）。

### 3.3 RT「我的价目」（C1 · US-RT-05 专属价复购 · 23-p5-c-c1 §4.1）

> 公开只读端点（无需登录）；实现归属 storefront 域（`RtStoreController.myPriceList` → `StoreFrontServiceImpl.getMyPriceList`），跨域经 pricing 出口 `listActiveRefsByPhone` / product 出口 `listForRtBySkuIds` 编排。

**`POST /api/v1/rt/my-pricelist`**

请求（手机号放 POST body，**不放 GET query**——防明文手机号落访问日志）：

```json
{ "storeId": null, "code": "rp123", "rtPhone": "13800006666" }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `storeId` | string\|null | 店铺 id（与 `code` 至少传一个，店铺解析同 `/rt/store`） |
| `code` | string\|null | 店铺码 = 租户简码 `tenantSimpleCode` |
| `rtPhone` | string | RT 手机号，必填（`@NotBlank`）；服务内 `PiiCrypto.phoneHmac` 盲查 |

响应 `R<RtPriceListVo>`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "rtPhoneLast4": "6666",
    "wholesalers": [
      {
        "wholesalerId": "1842...",
        "name": "xx批发",
        "items": [
          {
            "skuId": "1842...",
            "name": "雪花梨 5kg/箱",
            "spec": "5kg/箱",
            "mainImage": null,
            "unitPrice": 25,
            "moqPrice": 23,
            "moqQty": 5,
            "stockQty": 40,
            "customerPrice": 21.5,
            "expireAt": "2026-10-01T00:00:00",
            "source": "from_inquiry",
            "listed": true
          }
        ]
      }
    ]
  }
}
```

字段语义：

| 字段 | 说明 |
|---|---|
| `rtPhoneLast4` | 手机号尾号 4 位（归属提示）；**响应永不返回明文手机号** |
| `wholesalers[].wholesalerId/name` | 店内 ACTIVE 批发商（无价目行的商户不出组，组序 = 店内批发商序） |
| `items[].customerPrice` | 专属价现值（价目主价；BigDecimal 序列化为 number） |
| `items[].unitPrice/moqPrice/moqQty` | 公开价对照（unitPrice 划线次要展示） |
| `items[].source` | `manual`（商户设定）/ `from_inquiry`（议价沉淀） |
| `items[].expireAt` | 专属价失效时间；null=永久有效 |
| `items[].listed` | false=SKU 已下架（行仍返回，前端置灰禁提交） |
| `items[].stockQty` | 当前库存量；0=缺货（可缺货询，不拦提交） |

语义与过滤：价目 = 当前店全部 ACTIVE wholesaler × 该 hmac 的**有效**专属价行（`isActive`：status=ACTIVE 且未过期）；组内行按价目行 `createdAt` 倒序；过期/DISABLED/软删行不返回；空态（无任何有效专属价）返回 `wholesalers: []`，HTTP 200。

提交：价目行勾选数量后**沿用既有** `POST /api/v1/rt/inquiry`（现价快照 + WA 确认议价链路），**无新增提交端点**。

**零新错误码**：`rtPhone` 为空 → 通用参数校验 40001；店铺不存在/不可进 → 沿用 `/rt/store` 既有错误码。

---

## 4. 错误码（撮合配置相关，详见 05-error-codes.md）

| code | 常量 | 场景 |
|---|---|---|
| 50711 | `STOREFRONT_MAIN_SKU_LIMIT` | 主推商品数量超出上限（最多 20 个） |
| 50712 | `STOREFRONT_PIN_WA_LIMIT` | 置顶批发商数量超出上限（最多 5 个） |
| 50713 | `STOREFRONT_FEATURED_DUPLICATED` | 主推/置顶条目重复 |
| 50714 | `STOREFRONT_REF_INVALID` | 引用无效（非本店在售商品或非本店入驻批发商） |
| 50210 | `TENANT_NOT_FOUND` | 未找到您的租户/店铺，请先完成建仓 |
| 42101 | `PERMISSION_TENANT_001` | 您没有访问此租户数据的权限（非本租户 ACTIVE TA 操作配置） |
| 40001 | `VALIDATION_BASIC_001` | 参数校验失败 |

> 与 18-p5-design §5 一致（50711-50714）；公告错误码见 `api-contract-notify.md`（50501-50503）。

---

## 5. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-01 | 首版：固化 P5-A W4 契约——撮合配置 GET/PUT（TA 鉴权、覆盖保存、50711-50714 写前校验、顺序语义）+ RT 进店浏览出参扩展（featuredSkuIds/pinnedWholesalerIds/featured/pinned 与前置排序）。 |
| v1.1 | 2026-09-02 | 增 §3.3 RT「我的价目」（C1）：POST `/rt/my-pricelist` 只读查询 + RtPriceListVo 出参 + 零新错误码；提交沿用 `/rt/inquiry`。 |
