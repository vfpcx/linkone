package com.cangchu.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.cangchu.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * MyBatis Plus 配置：分页插件 + 字段自动填充
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /**
     * 仅对真正按租户隔离的业务表自动追加 tenant_id 条件（兜底防越权，G-2.2）。
     * 全局表（tenants/users 无 tenant_id 列）、跨租户访问表（user_roles 注册期 tenant_id 为 null、
     * sms_codes/login_sessions/password_history 账号域、tenant_applications/invite_codes/capacity_publish
     * 存在 OPS 跨租户或公开查询）一律忽略，避免误伤现有正常流程。
     */
    private static final Set<String> TENANT_FILTER_TABLES = Set.of(
            "stores",
            "tenant_settings",
            "wholesalers",
            "skus",
            "inventories",
            "stock_movements",
            "inbound_requests",
            "inquiry_requests",
            "outbound_requests",
            "customer_prices",
            "price_change_logs",
            // P2 入驻 Wave1：申请表含 tenant_id，纳入兜底隔离（TA 审批列表按租户可见）。
            // blacklist 为平台级共享表（PLATFORM_TABLE），按决策 O-6 不加入白名单。
            "wholesaler_applications",
            // P2 入驻 Wave2：退驻申请表含 tenant_id，同样纳入兜底隔离。
            "wholesaler_withdraw_applications",
            // P3 BE-W1（12 §4.1/§4.3）：仲裁单纳入兜底隔离。
            // OPS 跨租户查询（客诉仲裁）与 72h Job（系统态）无 TenantContext → 不注入，符合先例。
            "arbitrations",
            // notifications 不纳入租户行级过滤（P5-A W3 18 §4.2 语义修正，E2E 实证）：
            // 平台级公告站内信 tenant_id=null，登录态 TenantLine 注入 `tenant_id = ?` 会把它过滤掉，
            // 导致 TA/WK/WA 全部看不到平台公告。本表隔离边界是 recipient_user_id：
            // listMine/unreadCount/readAll 均按登录者本人 scope，markRead 有本人校验，无跨人/跨租户泄漏路径。
            // P3b T1-BE（13 §4.1 V19）：R3 登记纠错单纳入兜底隔离
            "inbound_corrections",
            // P3b T3-W1（13 §4.1 V20）：退货单纳入兜底隔离
            "return_requests",
            // P3b T3-W2（13 §4.1 V21）：盘点单两表纳入兜底隔离
            "count_sheets",
            "count_sheet_items",
            // P3b T4-W1（13 §4.1 V22）：批次登记簿纳入兜底隔离
            "batches",
            // P3b T4-W2（13 §4.1 V23）：清库单纳入兜底隔离
            "clearance_requests",
            // P4 W1（14 §4 V24）：计费规则版本链纳入兜底隔离
            "billing_rules",
            // P4 W2（14 §4 V25）：每日计费快照纳入兜底隔离
            // （DailySnapshotJob 系统态无 TenantContext → 不注入，符合 72h Job 先例）
            "daily_snapshots",
            // P4 W3（14 §4 V26）：账单四表纳入兜底隔离
            // （MonthlyBillJob/BillAutoConfirmJob 系统态无 TenantContext → 不注入，先例同上）
            "bills",
            "bill_items",
            "payment_records",
            "bill_disputes",
            // P5-A W4（18-p5-design §2.2 V36）：店铺撮合配置纳入兜底隔离
            // （storefront RT 匿名浏览无 TenantContext → 不注入，由 tenant 域 Service 显式 tenantId 过滤）
            "storefront_featured",
            // P5-D C3（24-p5-c-c3 §3.3 V39）：客户跟进档案两表纳入兜底隔离
            // （FollowupReminderJob 系统态无 TenantContext → 不注入，全量扫描按行内 tenant 入通知）
            "customer_followups",
            "followup_reminders"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 租户行级隔离：必须在分页插件之前
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.getTenantId();
                // 登录态推导出可信租户才追加条件；无可信租户（OPS 跨租户/公开）不注入
                return tenantId != null ? new LongValue(tenantId) : new NullValue();
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                if (tableName == null) {
                    return true;
                }
                // 仅过滤白名单内的租户业务表；且仅在存在可信租户上下文时过滤
                String name = tableName.replace("`", "");
                if (!TENANT_FILTER_TABLES.contains(name)) {
                    return true;
                }
                return TenantContext.getTenantId() == null;
            }
        }));

        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
                // 自动填充 tenant_id（若实体有此字段）
                Long tenantId = TenantContext.getTenantId();
                if (tenantId != null && metaObject.hasSetter("tenantId")) {
                    this.strictInsertFill(metaObject, "tenantId", Long.class, tenantId);
                }
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
