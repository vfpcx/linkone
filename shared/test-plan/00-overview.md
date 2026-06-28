# 仓储云 SaaS · 整体测试规划 v1

> 编写：Team Lead · 2026-06-11
> 依赖：PRD v1 (commit 99614e2) + 架构 v1 (6878408) + 后端骨架 WIP (8930479) + 前端骨架 WIP (c02f853)
> 状态：草案 → 待用户确认

---

## 0. 文档说明

本文档定义仓储云 SaaS 平台 v1 的**完整测试体系**：层次、范围、覆盖目标、用例清单、工具栈、CI 接入策略。

**关键决策**：MVP 试点期需要**最小够用、且能持续跑通**的测试集，不追求 100% 覆盖率。

---

## 1. 测试金字塔

```
        ┌──────────────────────┐
        │   E2E (5%)           │  Cypress / Playwright
        │   ~10 个核心业务流程  │  跑在预发环境，每晚 1 次
        ├──────────────────────┤
        │   API 集成测试 (30%) │  RestAssured / TestRestTemplate
        │   ~80 个接口 happy + │  跑在 H2 + Embedded Redis
        │   关键 error path    │  每次 PR 必跑
        ├──────────────────────┤
        │   组件/前端单元 (15%) │  Vitest + Vue Test Utils
        │   ~30 个业务组件      │  每次 PR 必跑
        ├──────────────────────┤
        │   后端单元 (50%)     │  JUnit 5 + Mockito + AssertJ
        │   ~150 个 service 方法│  每次 PR 必跑
        └──────────────────────┘
```

### 1.1 分层目标与边界

| 层 | 目标 | 不做的事 |
|---|---|---|
| **后端单元** | 业务规则、计算公式、状态机转换、权限校验逻辑 | 不连 DB、不连 Redis、不起 Web 容器；用 Mockito stub mapper |
| **API 集成** | controller → service → mapper → DB → response 的完整链路；多模块交互；事务回滚 | 不测前端、不测真实第三方（地图/ASR/短信全 mock） |
| **前端单元** | 业务组件渲染、props/emits、表单校验、错误码分段路由 | 不测路由跳转、不测真实 HTTP |
| **E2E** | 核心业务全链路（登录 → 申请 → 入库 → 出库 → 账单） | 不穷举所有页面、不测异常分支 |

### 1.2 场景维度（与分层正交）★ 新增

金字塔分的是**技术层次**（怎么测），场景分的是**业务情形**（测什么情况）。两者正交：**每一层都要覆盖多类场景**，不能只测 happy path。

```
            场景类型（横轴，正交于金字塔纵轴）
            S1正常  S2非法输入  S3边界  S4越权  S5状态机  S6重复/幂等  S7并发  S8依赖故障  S9超时
后端单元      ●        ●         ●       ●        ●          ●          —        —          —
API集成       ●        ●         ●       ●        ●          ●          ●        ●          ●
前端单元      ●        ●         ●       —        —          —          —        —          —
E2E          ●        ○         ○       ○        ○          —          —        —          —
            （● 必覆盖 / ○ 抽样覆盖 / — 该层不适用）
```

完整的场景分类、标准化用例格式、覆盖矩阵与具体用例见 **`02-scenario-test-plan.md`**。
**任何模块的测试用例清单（§5）都必须按 S1–S9 自检覆盖度，禁止只写正常流程。**

---

## 2. 后端测试详细规划

### 2.1 工程配置

| 项 | 规范 |
|---|---|
| 框架 | JUnit 5 + Spring Boot Test + AssertJ + Mockito |
| 数据库 | **H2 内存模式** + Flyway 自动建表（`spring.profiles.active=test`） |
| Redis | **embedded-redis** 启动随机端口（替代连本机 Memurai） |
| 短信 | `SmsUtil` 测试切面：写控制台 + 固定验证码 `"888888"` |
| 地图反查 | mock 直接返回固定坐标 |
| 包结构 | `src/test/java/com/cangchu/{module}/...Test.java` |
| 命名 | `XxxServiceTest`（单元） / `XxxControllerTest`（集成）/ `XxxIntegrationTest`（跨模块） |

### 2.2 用例清单（按模块）

下表是 **MVP P0 模块**的目标用例数。详细 case-by-case 见 §5。

| 模块 | 单元用例 | 集成用例 | 状态 |
|---|---|---|---|
| **Common 基础设施** | 15 | — | ❌ 未写 |
| **Account 账号** | 25 | 12 | ✅ 集成完成（10 个 controller test） |
| **Tenant 租户** | 20 | 14 | 🟡 集成部分完成（4/8 pass） |
| Product 商品/价格 | 25 | 15 | ❌ 未写（模块未实现） |
| Inventory 库存/流水 | 30 | 18 | ❌ 未写 |
| Document 单据 | 35 | 20 | ❌ 未写 |
| Billing 账单 | 20 | 12 | ❌ 未写 |
| Matchmaking 撮合 | 15 | 10 | ❌ 未写 |
| **MVP 合计** | **185** | **101** | — |

### 2.3 单元测试模板（service 层）

```java
@ExtendWith(MockitoExtension.class)
class TenantServiceImplTest {

    @Mock private TenantMapper tenantMapper;
    @Mock private StoreMapper storeMapper;
    @Mock private UserMapper userMapper;
    @Mock private SmsUtil smsUtil;
    @InjectMocks private TenantServiceImpl tenantService;

    @Test
    @DisplayName("apply: 正常提交 → 状态 PENDING")
    void apply_normal() {
        // given
        when(userMapper.selectById(123L)).thenReturn(mockUser());
        when(tenantMapper.insert(any())).thenReturn(1);
        // when
        Map<String, Object> result = tenantService.apply(123L, mockDto());
        // then
        assertThat(result.get("status")).isEqualTo("PENDING");
    }
}
```

### 2.4 集成测试模板（controller 层）

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
@Transactional  // 每个 case 自动回滚
class TenantControllerTest {

    @Autowired private TestRestTemplate restTemplate;
    @LocalServerPort private int port;

    // 每个 case 独立 setUp，不依赖 @Order 副作用
    @Test
    @DisplayName("US-TA-01: TA 自助注册仓库")
    void apply_pending() {
        TestFixtures.Ta ta = TestFixtures.registerTa(restTemplate, port);
        var resp = restTemplate.exchange(...);
        assertThat(resp.code()).isEqualTo(0);
    }
}
```

### 2.5 关键工具类

需要补：

```
src/test/java/com/cangchu/support/
├─ TestFixtures.java          # 注册 TA / WK / OPS / WA 等通用工厂
├─ TestPhoneGenerator.java    # 生成唯一合规手机号
├─ MockSmsCode.java           # 拦截 SmsUtil 返回固定 code
├─ TenantSetupHelper.java     # registerTa + apply + audit 一站
└─ AbstractIntegrationTest.java # 基类：注入 restTemplate / port / 公共断言
```

### 2.6 测试数据策略

| 选项 | 规则 |
|---|---|
| 手机号 | `13` + 5位 JVM 时间戳 + 4位自增 = 11 位，符合 `^1[3-9]\d{9}$` |
| 验证码 | 测试环境固定 `"888888"`（`SmsUtil` 在 test profile 不验真发码） |
| 雪花 ID | 测试环境用同一 worker，避免分配冲突 |
| 事务 | `@Transactional` 自动回滚；跨多 case 共享状态时改用 `@TestMethodOrder` + 显式 cleanup |
| Redis 数据 | 每个 case 用 `@BeforeEach` 清 db 0（embedded redis）|

---

## 3. 前端测试详细规划

### 3.1 工程配置

| 项 | 规范 |
|---|---|
| 框架 | Vitest + Vue Test Utils + @vue/test-utils |
| Mock HTTP | MSW（Mock Service Worker） |
| 覆盖目标 | 业务组件 100%、视图层 happy path 60%+ |
| 包结构 | 紧邻源文件 `Login.vue` ↔ `Login.test.ts` |

### 3.2 用例清单

| 模块 | 用例数 | 状态 |
|---|---|---|
| `@cangchu/ui-shared` 4 业务组件 | 16（每组件 4 个）| ❌ 未写 |
| `@cangchu/error-codes` 分段路由 | 7（七大类各一）| ❌ 未写 |
| `@cangchu/design-tokens` 注入验证 | 2 | ❌ 未写 |
| admin 表单校验（登录/注册/找回）| 12 | ❌ 未写 |
| admin Pinia auth store | 8 | ❌ 未写 |
| **MVP 合计** | **45** | — |

### 3.3 业务组件测试模板

```ts
// MoneyDisplay.test.ts
describe('MoneyDisplay', () => {
  it('renders integer + decimal with tabular-nums', () => {
    const wrapper = mount(MoneyDisplay, { props: { value: 2340.56 } });
    expect(wrapper.text()).toContain('¥2,340.56');
    expect(wrapper.classes()).toContain('tabular-nums');
  });

  it('shows negative in danger color', () => {
    const wrapper = mount(MoneyDisplay, { props: { value: -100 } });
    expect(wrapper.classes()).toContain('text-danger');
  });

  it('handles zero', () => { /* ... */ });
  it('handles undefined → fallback "—"', () => { /* ... */ });
});
```

### 3.4 API 拦截器测试

```ts
describe('http interceptor errorCode routing', () => {
  it('41xxx → clear token + redirect to /login', async () => { /* ... */ });
  it('42xxx → toast 无权限', async () => { /* ... */ });
  it('43xxx → toast + Retry-After 倒计时', async () => { /* ... */ });
  // ... 7 大类
});
```

---

## 4. E2E 测试详细规划

### 4.1 工程配置

| 项 | 规范 |
|---|---|
| 框架 | **Playwright**（多浏览器 + headless + 录屏） |
| 运行 | 预发环境，**每晚 1 次** + 发布前手动 1 次 |
| 数据 | 专用 E2E 租户，跑前 reset + 跑后 cleanup |

### 4.2 核心业务链路（10 条）

| # | 链路 | 涉及角色 |
|---|---|---|
| E1 | TA 自助注册仓库 → OPS 审核 → 登录 → 完成店铺设置 | TA + OPS |
| E2 | WA 申请入驻 → TA 审批 → WA 登录 → 上架 SKU | WA + TA |
| E3 | WA 提交入库申请 → WK 受理 → 登记入库 → 库存增加 | WA + WK |
| E4 | RT 扫码进店 → 浏览 → 提交询价 → WA 确认 → 自动转出库 → WK 出库 | RT + WA + WK |
| E5 | WK 现场代建入库 → WA 72h 内确认 → 库存生效 | WK + WA |
| E6 | WK 现场代建出库 → WA 通知 → 客诉链路 | WK + WA + OPS |
| E7 | ST 月度账单生成 → 调整 → 下发 → WA 接收 → 已收款登记 | ST + WA |
| E8 | TA 调整计费规则 → 当月分段计费验证 | TA + ST |
| E9 | RT 议价确认 → 沉淀客户专属价 → 下次进店看到专属价 | RT + WA |
| E10 | OPS 代建租户 → 短信临时密码 → TA 首登强制改密 → 上线营业 | OPS + TA |

### 4.3 单条用例模板

```ts
test('E1: TA 自助注册 → 审核 → 设置', async ({ page }) => {
  // 1. TA 注册
  await page.goto('/register?role=ta');
  await page.fill('[name=phone]', uniquePhone());
  await page.fill('[name=smsCode]', '888888');
  // ...
  await page.click('[data-test=submit]');

  // 2. 切换到 OPS 账号审核
  await loginAsOps(page);
  await page.goto('/admin/tenant?status=pending');
  await page.click(`[data-test=approve-${tenantId}]`);

  // 3. 验证 TA 能登录并访问工作台
  await loginAsTa(page);
  await expect(page).toHaveURL('/ta/dashboard');
});
```

---

## 5. 详细用例清单（MVP）

> 用 `US-XXX-YY` 引用 02-user-stories.md 中的故事，确保覆盖。

### 5.1 Account 模块（已实现）

| ID | 用例 | 类型 | 关联故事 | 状态 |
|---|---|---|---|---|
| AC-U-01 | 密码 bcrypt 编码正确 | 单元 | US-COMMON-01 | ❌ |
| AC-U-02 | 验证码生成 6 位数字 | 单元 | US-COMMON-01 | ❌ |
| AC-U-03 | 角色优先级解析 TA>ST>WK>WA>WE | 单元 | US-COMMON-02 | ❌ |
| AC-U-04 | 5 次密码错锁逻辑 | 单元 | US-COMMON-02 | ❌ |
| AC-I-01 | TA 自助注册 → 返回 token | 集成 | US-COMMON-01 | ✅ |
| AC-I-02 | 同手机号重复注册 → PHONE_DUPLICATE | 集成 | US-COMMON-01 | ✅ |
| AC-I-03 | 手机号格式错 → 40101 | 集成 | US-COMMON-01 | ✅ |
| AC-I-04 | 密码登录正常 | 集成 | US-COMMON-02 | ✅ |
| AC-I-05 | 密码错误 → 41001 | 集成 | US-COMMON-02 | ✅ |
| AC-I-06 | 验证码登录正常 | 集成 | US-COMMON-02 | ✅ |
| AC-I-07 | 改密 → 全 token 失效 | 集成 | US-COMMON-03 | ✅ |
| AC-I-08 | 找回密码两步走 | 集成 | US-COMMON-04 | ✅ |
| AC-I-09 | RT 免密验证码登录（首次自动注册） | 集成 | US-COMMON-06 | ✅ |
| AC-I-10 | 退出登录 → token 失效 | 集成 | US-COMMON-07 | ✅ |
| AC-I-11 | 多角色登录 → 返回 roles（数组，原 roleList 已更名） | 集成 | US-COMMON-02 | ✅ |
| AC-I-12 | 换绑手机号 → 全 token 失效 | 集成 | US-COMMON-05 | ❌ |

### 5.2 Tenant 模块（部分实现）

| ID | 用例 | 类型 | 关联故事 | 状态 |
|---|---|---|---|---|
| TN-I-01 | TA 自助注册仓库 → PENDING | 集成 | US-TA-01 | ✅ |
| TN-I-02 | OPS 审核通过 → ACTIVE | 集成 | US-OPS-01 | ✅ |
| TN-I-03 | OPS 代建租户 → 直接 ACTIVE + 短信 | 集成 | US-OPS-01b | ✅ |
| TN-I-04 | TA 查本店设置 | 集成 | US-TA-04 | 🟡 修中 |
| TN-I-05 | TA 改店铺设置（5 开关 + lng/lat） | 集成 | US-TA-04 | 🟡 修中 |
| TN-I-06 | TA 实时容量查询 | 集成 | US-TA-10 | 🟡 修中 |
| TN-I-07 | TA 生成店铺码 | 集成 | US-TA-02 | 🟡 修中 |
| TN-I-08 | TA 生成员工注册码 | 集成 | US-TA-03 | 🟡 修中 |
| TN-I-09 | 非 OPS 调 /admin → 41001 | 集成 | 鉴权边界 | ❌ |
| TN-I-10 | TA 查别人租户 → 50210 / 数据隔离 | 集成 | 多租户隔离 | ❌ |
| TN-I-11 | 同名仓库不可注册 | 集成 | 业务校验 | ❌ |
| TN-I-12 | 拍照开关变更 → 历史照片保留 | 集成 | US-TA-11 | ❌ |
| TN-I-13 | 批次开关切换 → 默认占位批次生成 | 集成 | US-TA-12 | ❌ |
| TN-I-14 | OPS 代建批发商 → 需 TA 授权 | 集成 | US-OPS-01c | ❌ |

### 5.3 Product / 价格模块（未实现）

| ID | 用例 | 类型 | 关联故事 |
|---|---|---|---|
| PR-U-01 ~ 06 | SPU/SKU CRUD | 单元 | US-WA-03 |
| PR-U-07 ~ 12 | 公开价 / 客户专属价匹配优先级 | 单元 | US-WA-10 |
| PR-U-13 ~ 18 | 议价沉淀逻辑 | 单元 | US-WA-10 |
| PR-U-19 ~ 25 | 批量调价 + 历史 | 单元 | US-WA-11 |
| PR-I-01 ~ 15 | controller 集成 | 集成 | — |

### 5.4 Inventory / 库存模块（未实现）

| ID | 用例 | 类型 | 关联故事 |
|---|---|---|---|
| IV-U-01 ~ 10 | FIFO 算法（批次启用 / 关闭 / 切换期）| 单元 | US-WK-02 + D29 |
| IV-U-11 ~ 18 | 库存数公式（入库 - 出库 + 盘盈 - 盘亏 - 退货）| 单元 | US-WK-03 |
| IV-U-19 ~ 24 | 托盘释放算法（ceil 比例 / 手动覆盖）| 单元 | 简化版 |
| IV-U-25 ~ 30 | 临期判定 + 自动每日扫描 | 单元 | US-WK-04 |
| IV-I-01 ~ 18 | controller 集成 | 集成 | — |

### 5.5 Document / 单据模块（未实现）

| ID | 用例 | 类型 | 关联故事 |
|---|---|---|---|
| DC-U-01 ~ 10 | 入库申请状态机（含 WK 代建 + 72h 自动接受） | 单元 | US-WA-04 + US-WK-01b |
| DC-U-11 ~ 18 | 出库申请状态机（含意向单自动转 + WK 代建） | 单元 | US-WA-05 + US-WK-02b |
| DC-U-19 ~ 25 | 询价状态机 + 议价沉淀联动 | 单元 | US-WA-06 |
| DC-U-26 ~ 30 | 盘点单 / 退货单 / 临期清库单 | 单元 | US-WK-03 + R5 + R19 |
| DC-U-31 ~ 35 | 整单优惠减免（D 决策）| 单元 | 用户补遗 |
| DC-I-01 ~ 20 | controller 集成 | 集成 | — |

### 5.6 Billing / 账单模块（未实现）

| ID | 用例 | 类型 | 关联故事 |
|---|---|---|---|
| BL-U-01 ~ 06 | 件·天 + 托盘·天 计费公式（含边界示例 5.1.6） | 单元 | US-TA-04 |
| BL-U-07 ~ 12 | 规则变更分段计费（R20） | 单元 | US-ST-R04 |
| BL-U-13 ~ 17 | 冲销条目（不删原条目）| 单元 | US-ST-R01 |
| BL-U-18 ~ 20 | 已收款冲销 / 已下发撤回 | 单元 | US-ST-R02 + R03 |
| BL-I-01 ~ 12 | controller 集成 | 集成 | — |

### 5.7 Matchmaking / 撮合模块（未实现）

| ID | 用例 | 类型 | 关联故事 |
|---|---|---|---|
| MM-U-01 ~ 05 | 容量公示算法（精确 / 模糊档） | 单元 | US-TA-10 |
| MM-U-06 ~ 10 | 基于位置推荐仓库（GCJ-02 距离）| 单元 | US-RT-06 |
| MM-U-11 ~ 15 | 容量告警订阅触发 | 单元 | US-WA-01b |
| MM-I-01 ~ 10 | controller 集成 | 集成 | — |

---

## 6. CI / CD 测试接入策略

### 6.1 触发时机

| 触发 | 跑什么 | 期望耗时 |
|---|---|---|
| 每次 PR | 后端单元 + 集成 + 前端单元 | < 3 分钟 |
| 合并 main | 同上 + 前端 build | < 5 分钟 |
| 每晚 02:00 | 全集（含 E2E）| < 30 分钟 |
| 发布前 | 全集 + 手动 smoke test | < 1 小时 |

### 6.2 失败策略

- 单元测试失败 → 阻塞 PR 合并
- 集成测试失败 → 阻塞 PR 合并
- E2E 失败 → 通知不阻塞，由值班人决定 hotfix
- 测试覆盖率下降 > 2% → 警告，不阻塞

### 6.3 工具链

| 用途 | 工具 |
|---|---|
| 后端测试运行 | `mvn test` |
| 前端测试运行 | `pnpm test` |
| 覆盖率 | JaCoCo（后端）+ V8 coverage（前端） |
| E2E | Playwright |
| CI | GitHub Actions |
| 报告 | Allure + 上传 OSS |

---

## 7. 实施路线图

| 阶段 | 周 | 产物 |
|---|---|---|
| Phase 1 | W1 | TestFixtures 工具类 + Tenant 集成测试全绿（18/18）|
| Phase 2 | W2 | Account / Tenant 补单元测试（45 个）|
| Phase 3 | W3-W4 | Product / Price 单元 + 集成测试（40 个）|
| Phase 4 | W5-W6 | Inventory / Document 单元 + 集成测试（103 个）|
| Phase 5 | W7 | Billing / Matchmaking 单元 + 集成测试（63 个）|
| Phase 6 | W8 | 前端 Vitest 全量 + E2E 10 条 |
| Phase 7 | W9 | CI 接入 + 覆盖率报告 |

---

## 8. 紧迫问题 · 当前阻塞

### 8.1 TenantControllerTest 4 个失败（50210）

**根因**：`registerTaApprovedTenant()` 调用链断了，TA 注册 → 申请 → OPS 审核通过后，TA 应该能查到自己的店铺，但实际返回"租户不存在"。

可能原因（**待源码 review 确认**）：
1. `TenantServiceImpl.getMyStore(userId)` 不是按 userId 找当前用户的租户，而是按 tenant_id（需查源码）
2. 申请时没把 user 和 tenant 双向绑定到 user_role 上
3. 审核通过的 SQL 漏字段

### 8.2 next step（确认后立刻做）

1. 读 TenantServiceImpl.getMyStore() 源码弄清 50210 根因
2. 修后端业务代码（不是测试代码）让链路完整跑通
3. mvn test 全绿
4. 落 commit
5. 然后启动其他模块

---

## 9. 你需要确认的

请回复以下几点：

| # | 问题 | 选项 |
|---|---|---|
| 1 | 测试金字塔比例 50/15/30/5 是否合理 | OK / 改 |
| 2 | MVP 阶段不追求 100% 覆盖，185 单元 + 101 集成 + 45 前端 + 10 E2E 是否够 | OK / 加减 |
| 3 | E2E 用 Playwright 还是 Cypress | Playwright / Cypress |
| 4 | 是否在 §8 立即修 TenantControllerTest 50210 问题 | 立即修 / 暂搁置 |
| 5 | Phase 1-7 路线图节奏 | OK / 调整 |

确认后我就分阶段推进。
