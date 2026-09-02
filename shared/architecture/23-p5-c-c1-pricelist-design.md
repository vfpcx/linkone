# 23 · P5-C1「RT 我的价目（专属价复购）」设计定稿 v1.0

> 编写：Team Lead · 2026-09-02
> 依据：`product/17-p5-c-smallpool.md` v1.3 §1 C1（D-C-3/4/5 用户确认采纳 2026-09-02）+ 代码实测（pricing/storefront/document/notify 域 2026-09-02）
> 定位：**C · 小项池第一波（C1 先发）**——无新表、无迁移、无新错误码；一波 = 后端端点+测试 → 前端 → 契约/回归/提交
> 状态：设计定稿，进入实现

---

## 1. 背景与目标

- **业务**：RT 买家（匿名为主）在店铺页输入手机号提交询价（P1 闭环）。P2 已建成客户专属价体系：WA 可为 RT 手机号设专属价（`customer_prices`），询价确认价可沉淀为专属价（议价）。RT 现有浏览只展示「进店页在售 SKU」，无「属于我的价目」视图。
- **用户拍板（2026-09-02）**：复购方式**不走**「复制历史询价单」（已否决），改为**对着自己的客户专属价目发起询价**。价目即 `customer_prices` 有效行，不另建价目实体（D-C-4）。
- **本波范围**：RT「我的价目」查询端点（当前店 × 该手机号有效专属价，按 wholesaler 分组）+ RT 店铺页价目抽屉（勾选/填量提交询价，提交链路复用 `submitByRt`，取价=当前匹配价即专属价）。**不做**：历史单列表/意向单页/导出打印（见 product/17 §7）。

## 2. 现状事实（代码实测 2026-09-02）

| 项 | 事实 | 出处 |
|---|---|---|
| 价目底座 | `customer_prices`：tenant/wholesaler/sku/`rt_phone_hmac`+`rt_phone_last4`（**无明文列**，V34 已删）+ `unit_price` + `status`(ACTIVE/DISABLED/EXPIRED) + `source`(**manual/from_inquiry**，无 batch)+`expire_at`+软删；唯一键 `(wholesaler_id, rt_phone_hmac, sku_id)`；判活=`ACTIVE && (expireAt==null \|\| expireAt>now)` | V9/V30~V34、CustomerPrice.java |
| 盲查 | `PiiHmacQueries.customerPrice(wh, hmac, skuId, status)` / `customerPriceRows(wh, skuId, hmac)`；hmac 由 `PiiCrypto.phoneHmac(phone)` 单入口 | common/pii |
| 匹配价 | `PricingService.resolvePrice(wholesalerId, skuId, rtPhone, qty)`：专属价命中优先（与 qty 无关），否则公开价（`qty>=moqQty?moqPrice:unitPrice`）；缓存 `price:match:{wh}:{hmac}:{sku}` TTL 60s | PricingServiceImpl L326/518 |
| WA 侧看价目 | 已有 `GET /api/v1/tenant/customer-prices?wholesalerId=` → `listCustomerPrices` → `List<CustomerPriceVo>`（rtPhone 打码 `****+last4`） | PricingController |
| RT 浏览 | `/api/v1/rt/**` 公开（不在 SaInterceptor include）；`RtStoreController` 有 `currentRtPhone()`（登录态可空）；`StoreFrontServiceImpl.getStorePage/listSkus/buildOnSaleSkus` 已按登录 RT 下发 `matchedPrice` | storefront |
| SKU 展示 | `StoreSkuVo`：name/spec/mainImage/unitPrice/moqPrice/moqQty/stockQty/matchedPrice/featured——公开价对照字段齐备；在售列表仅 qty>0 且 listed | storefront/vo |
| RT 提交 | `POST /api/v1/rt/inquiry` → `submitByRt(SubmitInquiryDto)`：`{code?/storeId?, wholesalerId, rtPhone, items[{skuId,qty}]}`，单事务解析店铺+建单+现价快照 | RtInquiryController/InquiryServiceImpl |
| 前端 | `rt/Store.vue`：进店浏览 + `qtyMap` 全局数量 + 页脚 `rtPhone` 输入 + `submit()`（PHONE_RE 校验，逐组提交 `rtApi.submitInquiry`）；`api/rt.ts` 仅 getStore/submitInquiry | frontend |

## 3. 设计决策

| # | 决策 | 理由 |
|---|---|---|
| D-RP-1 | 查询用 **POST `/api/v1/rt/my-pricelist`**，手机号放 body（**不入 GET query/log**） | PII：URL query 明文手机号会进访问日志 |
| D-RP-2 | 编排放 **storefront 域**（复用店铺→tenant→wholesaler→sku 快照数据路径）；`customer_prices` 经 **pricing 编排出口**轻量 VO 接入 | G-S1：跨域不直连 Mapper；沿用 `TenantBatchConfigVo`/`listCustomerPrices` 编排先例 |
| D-RP-3 | 明文手机号只在 storefront service 内转 hmac（`PiiCrypto.phoneHmac`）；响应回 `rtPhoneLast4`（归属提示）与行内 `****xxxx`，**永不返回明文** | PII 收口；价目归属需可见 |
| D-RP-4 | 价目范围 = 当前店全部 **ACTIVE wholesaler** × 该 hmac 的**有效专属价行**（`isActive()` 过滤）；按 wholesaler 分组；某 wholesaler 无价目行则不出组；组内行 createdAt 倒序 | 与店铺浏览的分组视角一致 |
| D-RP-5 | 行状态：SKU 已下架/软删 → `listed=false`（置灰禁提交）；库存 0 **不拦**（询价允许缺货询，仅提示） | 与询价提交流程能力对齐 |
| D-RP-6 | **零新错误码**：手机号非空走 `@NotBlank`（与 SubmitInquiryDto 同口径）；店铺解析/归属复用 `/rt/store` 既有异常 | 小项池原则 |
| D-RP-7 | 安全自检（05-secure-coding-guardrails）：公开只读端点；仅 code/storeId+phone 入参；hmac 盲查；不返回他人数据；日志不落明文（S2/S4） | — |

## 4. 契约（草案 → 实现后以实测为准）

### 4.1 `POST /api/v1/rt/my-pricelist`
请求（公开端点，无需登录）：
```json
{ "code": "abc12", "storeId": null, "rtPhone": "138****8000" }
```
- `storeId` 与 `code` 至少一（与 /rt/store 同解析）；`rtPhone` 必填非空。

响应 `R<RtPriceListVo>`：
```json
{
  "rtPhoneLast4": "8000",
  "wholesalers": [
    {
      "wholesalerId": 1,
      "name": "xx批发",
      "items": [
        {
          "skuId": 101,
          "name": "雪花梨 5kg/箱",
          "spec": "5kg/箱",
          "mainImage": "https://...",
          "unitPrice": 25.00, "moqPrice": 23.00, "moqQty": 5,
          "stockQty": 40,
          "customerPrice": 21.50,          // 专属价现值
          "expireAt": "2026-10-01T00:00:00",
          "source": "from_inquiry",        // manual | from_inquiry
          "listed": true                   // false=SKU 已下架（置灰）
        }
      ]
    }
  ]
}
```
- `wholesalers[].name` 与店铺页一致；组顺序 = 店内 ACTIVE wholesaler 顺序；`items` 按行 createdAt 倒序。
- 空态：该手机号无任何有效专属价 → `wholesalers: []`（前端展示「暂无专属价目…按公开价询价」），HTTP 200。

### 4.2 无新表/迁移/错误码/通知。

## 5. 后端实现要点

1. **pricing 编排出口**：`PricingService` 新增 `List<CustomerPriceRef> listActiveRefsByPhone(Long wholesalerId, String rtPhoneHmac)`（`CustomerPriceRef` 轻量 VO：skuId/unitPrice/source/expireAt/createdAt，仅回 ACTIVE 且未过期行）；`PricingServiceImpl` 用 `PiiHmacQueries.customerPriceRows` 查询 + `isActive()` 过滤，**不 join sku**。
2. **storefront 编排**：`StoreFrontService` 新增 `RtPriceListVo getMyPriceList(Long storeId, String code, String rtPhone)`：
   - 店铺解析 → tenant + ACTIVE wholesaler（复用 `getStorePage` 内部路径）；
   - `rtPhoneHmac = piiCrypto.phoneHmac(rtPhone)`；`rtPhoneLast4 = piiCrypto.last4(rtPhone)`；
   - 逐 wholesaler 调 pricing 出口拿价目行 → 按 skuId 批量取 SKU 快照（**含下架**：name/spec/mainImage/unitPrice/moqPrice/moqQty/status）→ 叠加 stockQty（与 `buildOnSaleSkus` 同源库存读数）→ 组 VO（`listed = sku 在售`）。
   - 实现前先核：SKU 批量取数方法与「在售」判定字段、库存读取方法（复用现 storefront 路径，避免重复实现）。
3. **controller**：`RtStoreController` 新增 `@PostMapping("/my-pricelist")`（`storeId/code/rtPhone` 校验同 `submitByRt` 口径）。**不**新增公开 GET，避免明文手机号落日志。
4. 日志：不打印 rtPhone 明文（debug 仅 hmac/wholesaler/skuId）。

## 6. 测试要点（新增 `RtPriceListScenarioTest`，@SpringBootTest + 场景造数同现测试样板）

| 用例 | 场景 | 断言 |
|---|---|---|
| RP-01 | manual + from_inquiry 双来源价目 | 均入清单；组内字段正确（customerPrice/expireAt/source） |
| RP-02 | 过期行（expireAt 过去）/DISABLED | 不出现在清单 |
| RP-03 | A/B 两 wholesaler 各有价目 | 两组返回；无价目 wholesaler 不出组 |
| RP-04 | 跨店隔离 | 同 phone 换 code（别店）→ 空列表 |
| RP-05 | 明文不落响应 | 响应 JSON 无 rtPhone 明文；rtPhoneLast4 正确 |
| RP-06 | SKU 下架 | 行仍返回且 listed=false |
| RP-07 | 手机号空 | 400（@NotBlank）；code 不存在 → 复用店铺解析错误 |
| RP-08 | 纯读无副作用 | 调用后无新 inquiry、库存/批次无变化 |
| RP-09 | 提交回归 | 价目所见 customerPrice 提交询价后快照=专属价（沿用现有 submitByRt 测试，补一条联动） |

## 7. 前端实现要点

1. `api/rt.ts` 增 `getMyPriceList({code, rtPhone})`（POST `/rt/my-pricelist`）。
2. `@cangchu/api-types` 增 `RtPriceList/RtPriceGroup/RtPriceItem`。
3. `rt/Store.vue`：
   - 页脚 `rtPhone` 输入旁加「**我的价目**」按钮：未填/格式错 → 复用现有手机号校验提示；通过 → 拉取 → 抽屉；
   - 抽屉（移动端全屏容器）：标题「我的价目（尾号 xxxx）」；按 wholesaler 分组列表，行含 图/名/规格/公开价（单价·起批价/量标注）/「专属价」高亮徽标（+来源徽标：议价/商户设定）+ 有效期 + 状态（下架置灰禁勾选）；行数量步进（起批量 min 提示）；
   - 组底部「提交本组询价」→ 复用现有 `submit()` 链路（`submitByRt` 现价快照=专属价）；提交成功复用现有成功态 + resetForNext；
   - 空态：`wholesalers=[]` → 「暂无专属价目，可在店铺页按公开价询价」。
4. 不做：历史单列表/导出/短信。

## 8. 验证清单（本波 · 实现后回填）
- [x] 后端 `mvn test`：新增 RtPriceListScenarioTest 12/12 绿（RP-01~09 + 出口冒烟 2 例）2026-09-02
- [x] 前端 `typecheck`：`vue-tsc --noEmit`（lint 0 error）
- [x] 契约文档补齐：`api-contract-storefront.md` §3.3
- [x] 全量回归 **517 全绿**（500 基线 + C1 12 + C3 5，0 失败 0 错误）；提交随 C 小项池双波 docs 收官

## 9. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-09-02 | 首版：C1 设计定稿（POST my-pricelist + storefront 编排 + RT 价目抽屉） |
| v1.1 | 2026-09-02 | 实现记录：pricing 出口 `listActiveRefsByPhone` + product 出口 `listForRtBySkuIds` + storefront `getMyPriceList` + `RtStoreController POST /my-pricelist`；RtPriceListScenarioTest 12/12 绿；前端抽屉（rt/Store.vue + api/rt.ts + api-types rt.ts）；契约 §3.3。 |
