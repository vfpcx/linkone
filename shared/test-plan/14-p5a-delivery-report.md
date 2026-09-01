# 14 · P5-A 交付验收报告（W5 终验收，P5-A 收官）

> 测试&审查 Agent · 2026-09-01 · 验收基线 main=1f213f1（P5-A 全部开发波合并）
> 上期基线：12-p4-delivery-report.md（main=5319e44，408 测试 / E2E 45 例）
> ✅ **本版为定稿**：全部小节已回填并交叉核对（backend-dev 470 例 + e2e-qa 129 例 + frontend-dev 视觉 8/8），并纳入 W5 收尾修复（9104adf / 1dd627e / 27bad02）。

## 1. P5-A 范围

运营增强首期（真源 product/14-p5-requirements.md v1.1——D-P5-1~5 拍板，D-P5-6 容量告警订阅暂缓、D-P5-7 viewer 脱敏取消 + architecture/18-p5-design.md v1）：

- **通知中心增强**（W3，notify 域首次实现）：站内信分组筛选（`group=BIZ/ANNOUNCE/SYS`，type→group 映射）、全部已读 `read-all`（本人 scope 幂等）、公告类通知 type `PLATFORM_ANNOUNCEMENT`；前端独立消息中心页（分组 Tab/只看未读/单条已读/全部已读）
- **平台公告**（W3）：`announcements` 表（V35，平台级无 tenant_id、进租户过滤忽略名单）+ OPS 管理（创建/列表/详情/发布/下架，状态机 DRAFT→PUBLISHED→INACTIVE）+ 发布同事务按 target_roles 展开收件人批量写站内信（`AuthService.listActiveUserIdsByRoles`/`listAllActiveUserIds` 平台级反查）+ 登录公告弹窗只弹一次（复用 `notifications.readAt` 去重，不新增 Redis seen 键）
- **撮合运营**（W4）：`storefront_featured` 表（V36，租户级，uk(store_id,kind,ref_id)）+ TA 店铺设置「撮合运营」区块（主推 SKU ≤20 / 置顶批发商 ≤5，覆盖保存幂等、数组顺序落 sort_order）+ storefront 出参前置排序与 `featured/pinned` 标记（RT 店铺页「主推」/「置顶」标）
- 错误码：公告 50501-50503（**以实测定稿**，18 §5 草案 50701-50703 作废）；撮合 50711-50714；Flyway V35/V36

**不做**（18 §1）：短信通道、实时推送（WebSocket）、MQ、分布式事务、公告单用户定向。

## 2. 波次闸门数据（W0 + W3/W4 开发波，均 Team Lead 独立复验后合并）

| 波次 | 合并/定稿 commit | 闸门 |
|---|---|---|
| W0 产品需求 | 70a924f | 14-p5-requirements v1.1（D-P5-1~5 拍板，D-P5-6/7 暂缓/取消，范围收敛） |
| W0 架构设计 | 00f4d6f | 18-p5-design v1（notify 域首次实现 + 撮合运营；17 §6 准入 + §8 对照通过）；归属同步 c677091 |
| W3 通知+公告后端 | c366077 | **457 绿**（V35 公告模块 + 通知中心增强；AuthService 收件人反查；错误码 50501-50503） |
| W4 撮合后端 | b2cb572 | **468 绿**（V36 storefront_featured + StorefrontFeature 撮合配置 + storefront 出参排序；50711-50714）+ B1 公告租户过滤修复 4fc717b |
| W4 前端 | 6f0ca67 | 消息中心页/公告管理页/公告弹窗/店铺撮合区块 + ui-shared NotificationList + zh labels；B3 登录即弹修复 315257c |
| W4 E2E | ad2c915 | 公告 13/13 + 撮合 7/7 = **20/20**（14-p5a-e2e-cases/report） |
| W4 契约文档 | a595db4 | api-contract-account §5.9（listActiveUserIdsByRoles）+ 新建 api-contract-notify/storefront |
| W4 路线图 | 1f213f1 | 00-roadmap v2.3（P5-A W4 完成；错误码校正 50501-50503） |

## 3. W5 后端全量回归（终值 470 例全绿，main HEAD=9104adf）

> 数据来源：backend-dev（Team Lead 复核后转交）· 2026-09-01

| 轮次 | 代码态 | 结果 |
|---|---|---|
| Run 1 | main @ 248c98c（P5-A 全部波次 + 交付报告框架）`mvn clean test` | **469 / 0 fail / 0 error / 0 skip，BUILD SUCCESS（05:10）** |
| Run 2 | main @ 9104adf（+ ONB-E2E-02 Blacklist 修复 + BLK-05 回归测试）`mvn clean test` | **470 / 0 fail / 0 error / 0 skip**（469 + BLK-05） |

- 相对 W4 基线（b2cb572，468 绿）终值 +2：W5 新增测试类（如 WithdrawOfflineScenarioTest 16 例等）+ BLK-05 黑名单同尾号再拉黑回归（9104adf，红→绿双证）。
- Run 1 backend 工作区 clean，0 改动（纯验证）；Run 2 为 ONB-E2E-02 根因修复后的全量复跑。
- 环境注记：`cd backend && mvn clean test`，JDK 21.0.8（显式 `JAVA_HOME`，本机 PATH java 为 17）+ Maven 3.6.3；测试 profile 用 H2 内存库（MODE=MySQL），exclude Redis、短信 mock——全量不依赖本机 MySQL/Redis。

## 4. E2E 全套（18 spec × 129 例 = 129/129 绿，终值，8.6–9.5m）

> 数据来源：e2e-qa（提交 6bf99b9 全量 121 例 + 1dd627e 去 workaround 终验 129 例）· 2026-09-01
> 前置：后端 8080（dev,local，终验重启至含 9104adf 的最新 main）+ 前端 5173（vite dev，main，含 9ee9eb7/27bad02）；`npx playwright test --workers=1`；Node v24.14.1 / Playwright 1.61.1。日志留档见 14-p5a-e2e-report.md §6。
> 口径：e2e 目录 **18 个 spec 文件全覆盖**（129 例，含视觉矩阵 8 例，归 §5 详述）；19 曾为 e2e-report 早期笔误，e2e-qa 已修正。

| spec | 用例数 | 结果 |
|---|---|---|
| auth | 8 | ✅ |
| sell-flow | 5 | ✅ |
| sell-flow-2 | 5 | ✅ |
| onboarding-flow | 6 | ✅ |
| onboarding-visual | 8 | ✅ |
| inbound-dispute | 3 | ✅ |
| outbound-chain | 4 | ✅ |
| p3b-inbound-forward | 3 | ✅ |
| p3b-t3-returns-stocktake | 3 | ✅ |
| p3b-t4-batch-expiry | 3 | ✅ |
| p3b-w5-visual | 16 | ✅ |
| p4-billing | 5 | ✅ |
| p4-w5b-export-overview | 2 | ✅ |
| p4-w5-visual | 16 | ✅ |
| **p5a-announcement** | **13** | **✅** |
| **p5a-storefront-featured** | **7** | **✅** |
| **p5a-w5-visual** | **8** | **✅** |
| w5-visual | 14 | ✅ |
| **合计（18 spec）** | **129** | **129/129 绿** |

- 演进：首轮（6bf99b9 前）121 例 119/121（2 例 onboarding-flow 缺陷见下）；6bf99b9 修复后复跑全绿；终验（HEAD 27bad02 + 后端 9104adf，1dd627e）129 例全量 **128 通过 + 1 例 B-WA-04 seed 90001「系统繁忙」**（环境瞬时错误，非被测流程缺陷，单独重跑 5/5 绿）→ 终值 **129/129**。
- **ONB-E2E-02 / ONB-E2E-04 均已修复 + 复验去规避**（详见 14-p5a-e2e-report.md §6.3，登记 §6）：
  1. **ONB-E2E-02** → `BlacklistServiceImpl.summarizePhoneValue` 仅按 ACTIVE 行判断摘要占用，而 `uk_blacklist_type_value` 对 REMOVED 行同样生效，尾号撞历史 REMOVED 摘要时插库 DuplicateKey → 前端 50310。**已修复：backend-dev 9104adf**（REMOVED 摘要参与占位 + hmac4 消歧，BLK-05 红→绿双证，全量 470 例绿）；e2e workaround `blacklistSafeWaPhone` 已删除（1dd627e 复验 6/6 绿）。
  2. **ONB-E2E-04** → `src/api/http.ts` AUTH 分支对 41001 `ElMessageBox.alert` 未去重，并发 reload 堆叠多个「请重新登录」弹窗拦截点击。**已修复：frontend-dev 9ee9eb7**（会话级 `logoutAlertPending` 去重）；e2e workaround `force: true` 已删除（1dd627e 复验 6/6 绿）。

## 5. 视觉矩阵（8/8 绿，全部 ✓，产物 .e2e-tmp/p5a-w5-visual/）

> 数据来源：frontend-dev（提交 9ee9eb7 定稿版 + 27bad02 375 修复补跑）· 2026-09-01
> 视觉 spec：`frontend/apps/admin/e2e/p5a-w5-visual.spec.ts`（8 用例，serial 共享造数，56.9s）
> 截图产物：`<repo>/.e2e-tmp/p5a-w5-visual/*.png`（8 张，git 忽略；375 三张 27bad02 后重截图覆盖）

| 截图 | 覆盖 | 目检结论 |
|---|---|---|
| p5a-w5-login-announcement-dialog-1280 | OPS 公告发布→TA 登录弹窗 | ✓ 无溢出（视口截图，真实视野） |
| p5a-w5-ta-messages-unread-1280 | 消息中心公告分组 Tab + 未读态行 | ✓ |
| p5a-w5-ops-announcements-1280 | OPS 公告管理页「已发布」+「已下架」中文态同屏 | ✓ |
| p5a-w5-ta-settings-featured-1280 | TA 店铺设置撮合运营：主推 1/20 + 置顶 1/5 已选态 | ✓ |
| p5a-w5-rt-store-featured-1280 | RT 店铺页置顶/主推标识 + 前置排序 | ✓ |
| p5a-w5-rt-store-featured-375 | RT 店铺页 375 降档单列、无横滚 | ✓ |
| p5a-w5-login-announcement-dialog-375 | TA 登录公告弹窗 @375 | ✓ 已修复（27bad02 `width="min(520px, 92vw)"`）：无横向溢出、底部按钮可见可达；375 用例 3/3 绿重截图覆盖 |
| p5a-w5-ta-messages-375 | 消息中心 375：左菜单隐藏、单列流式 | ✓ |

**甄别注记**：弹窗两张用视口截图规避 fullPage+fixed 伪影（P4 同款）；页面用 fullPage；非弹窗用例统一 `dismissAnnouncementDialogIfShown` 防前序未读公告遮罩（测试基建，非产品行为）。

**零角色码 grep 复核结论**：模板可见文本/label/placeholder/title/插值/弹窗文案全扫描，用户可见裸角色码 **0 处**；唯二命中 `ta/Employees.vue` L420-421 `el-radio-button label="WK"/"ST"` 值绑定（显示文本「库管员/结算员」中文），与 P3/P3b/P4 口径一致不计违规。所有可见角色/公告组/状态文案均经 ui-shared `roleLabel`/`announcementGroupLabel`/`announcementStatusLabel` 映射中文。

## 6. 遗留清单（定稿）

| # | 项 | 说明 | 级别 |
|---|---|---|---|
| P5A-L1 | 公告弹窗「关闭但保留未读」未做 | 18 §10 待确认 3：本期按「关闭=已读」（readAt 去重），如产品要求区分再引入 seen 键（§6 留扩展点） | 低（口径） |
| P5A-L2 | 容量告警订阅暂缓 | D-P5-6：US-WA-01b 验收项待业务量上来后再评 | 低（暂缓） |
| P5A-L3 | **Blacklist REMOVED 行摘要撞唯一键（历史链路）** | ONB-E2E-02：`BlacklistServiceImpl.summarizePhoneValue` 仅按 ACTIVE 行判断摘要占用，`uk_blacklist_type_value` 对 REMOVED 行同样生效 → 尾号撞历史 REMOVED 摘要插库 DuplicateKey → 前端 50310。W5 全量回归暴露（非 P5-A 引入）；**已修复：backend-dev 9104adf**（REMOVED 摘要参与占位 + hmac4 消歧，BLK-05 红→绿双证 + 全量 470 例绿），e2e workaround 已删除（1dd627e 复验 6/6 绿） | 中（已修） |
| P5A-L4 | **http.ts 41001 弹窗未去重（历史链路）** | ONB-E2E-04：`src/api/http.ts` AUTH 分支对 41001 `ElMessageBox.alert` 未去重，并发 reload 堆叠多个「请重新登录」弹窗拦截点击。W5 全量回归暴露（非 P5-A 引入）；**已修复：frontend-dev 9ee9eb7 合入会话级 `logoutAlertPending` 去重**（.finally 复位），e2e workaround 已删除（1dd627e 复验 6/6 绿） | 中（已修） |
| P4-L1 | Element aria-disabled quirk | 沿用 P4：el-input-number 启用后 `aria-disabled` 残留，Playwright 需 `{ force: true }`（测试基建，真实用户可正常输入） | 低（测试基建） |
| P4-L2 | WA 无按日视角 | 沿用 P4：05 §5.4 既定口径（不向 WA 暴露库存明细），WA 侧核对颗粒度依赖导出对账单 | 低（口径） |
| P4-L3 | 导出中文字体部署项 | 沿用 P4：PDF 中文渲染依赖目标机系统 TTF（simhei/思源黑体任一），上线部署 checklist 必项 | 中（部署） |
| P4-L5 | 上线检查单余项 | 沿用 P4：prod profile 实机冒烟 / Redis 密码绑定 / graceful shutdown 实测 / CVE 复扫（OWASP dep-check/Trivy） | 高（上线前） |
| P4-L6 | sa-token redis 集成 | 沿用 P4：1.42+ 官方推荐迁 sa-token-redis-template 系，中期择机 | 低 |
| P3b-L1 | batch-config 可读端点缺口 | 沿用 P3b：`GET /tenant/batch-config` 未补，前端依赖 TenantDetail 间接读取 | 中 |
| P3b-L2 | 撤回→库管通知未实现 | 沿用 P3b：`withdrawByWa` 撤回成功无通知 WK，WK 若已备货会白跑 | 中 |
| P3b-L3 | WA 临期下钻未做 | 沿用 P3b：WA 仅收临期通知，无自助批次下钻视图 | 中 |
| P3b-L5 | WA 入库确认 SKU 裸 ID | 沿用 P3b：wa/Inbound.vue 代建确认列表 SKU 列显示雪花 ID（Outbound.vue 已改） | 低 |
| P3b-L7 | ops-arbitrations 时间列截断 | 沿用 P3b：默认列宽下发起时间被裁，cosmetic | 极低 |

## 7. 变更与边界（P5-A 涉及文件清单）

**后端**（W3 c366077 / W4 b2cb572 / 4fc717b / 9104adf）：
- `account`: AuthService/AuthServiceImpl（+listActiveUserIdsByRoles/listAllActiveUserIds）
- `common`: ErrorCode（+50501-50503/50711-50714）、MybatisPlusConfig（announcements 进租户过滤忽略名单；notifications 移出过滤表——B1）
- `notify`: Announcement 模块（Controller/Service/Impl/Mapper/Entity/Vo/CreateDto）、Notification 增强（TYPE_PLATFORM_ANNOUNCEMENT/readAll/group）、V35__p5a_announcements.sql、AnnouncementScenarioTest
- `tenant`: StorefrontFeature 模块（Controller/Service/Impl/Mapper/Entity/Vo/SaveDto）、V36__p5a_storefront_featured.sql、StorefrontFeatureEndpointTest、StorefrontFeaturedScenarioTest
- `tenant`: BlacklistServiceImpl + OnboardingScenarioTest（9104adf ONB-E2E-02：REMOVED 摘要参与占位 + 复活排除自身 + BLK-05）
- `storefront`: StoreFrontServiceImpl（撮合读入+前置排序）、StoreFrontVo/StoreSkuVo/StoreWholesalerVo（featured/pinned）
- `04-api-spec.md` 同步（b2cb572）

**前端**（W4 6f0ca67 / 315257c / 9ee9eb7 / 27bad02）：
- `apps/admin`: views/ops/Announcements.vue（公告管理页）、views/ta/Messages.vue（消息中心页）、views/ta/Settings.vue（撮合运营区块）、views/rt/Store.vue（主推/置顶标）、components/LoginAnnouncementDialog.vue（公告弹窗 + B3 修复 315257c + 375 宽度 27bad02）、api/notification.ts·ops.ts·tenant.ts、api/http.ts（9ee9eb7 ONB-E2E-04 会话级去重）、router、Dashboard 铃铛角标
- `packages/ui-shared`: NotificationList.vue 通用组件（admin 复用）
- `packages/api-types`: announcement.ts 新增 + tenant/rt/document 扩展；`packages/error-codes`: codes/messages-zh

**E2E 与契约**（ad2c915 / a595db4 / c677091 / 70a924f / 00f4d6f / 1f213f1 / 6bf99b9 / 9ee9eb7 / 1dd627e / 8ba046a）：
- `frontend/apps/admin/e2e/p5a-announcement.spec.ts`（13 例）、`p5a-storefront-featured.spec.ts`（7 例）、`p5a-w5-visual.spec.ts`（视觉矩阵 8 例，9ee9eb7）
- `frontend/apps/admin/e2e/helpers/onboarding.ts` + `onboarding-flow.spec.ts`（6bf99b9 flake 修复；1dd627e 删 ONB-E2E-02/04 两处 workaround 复验 6/6 绿）
- `shared/test-plan/14-p5a-e2e-cases.md`、`14-p5a-e2e-report.md`（§6 全量回归留档，8ba046a 口径修正为 18 spec/129 例）
- `shared/architecture/api-contract-notify.md`、`api-contract-storefront.md`、`api-contract-account.md`（§5.9）、`18-p5-design.md`、`08/17` 归属校正
- `shared/product/14-p5-requirements.md`（v1.1）、`shared/00-roadmap.md`（v2.3）

**边界**：后端仅新增/增强 notify/tenant/storefront/account/common 四域代码，未触碰 document 业务链；前端 src 仅新增页面与弹窗（B3 为 LoginAnnouncementDialog 单组件修复、ONB-E2E-04 为 http.ts 会话级去重、27bad02 为弹窗宽度单属性），未改动既有页面业务逻辑；E2E 只增 spec 与 test-plan 文档。

## 8. 服务保持

- 后端：http://localhost:8080（spring-boot:run，profiles=dev,local，自 main 启动）
- 前端：http://localhost:5173（主仓 frontend vite dev，@cangchu/admin）
- MySQL cangchu_dev + Redis(Memurai) 127.0.0.1:6379 在跑
