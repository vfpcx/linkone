package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.TestUniq;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SmsUtil;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.CustomerReminderDto;
import com.cangchu.document.dto.CustomerRemarkDto;
import com.cangchu.document.entity.CustomerFollowup;
import com.cangchu.document.entity.FollowupReminder;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.CustomerFollowupMapper;
import com.cangchu.document.mapper.FollowupReminderMapper;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.service.CustomerFollowupService;
import com.cangchu.document.vo.CustomerDetailVo;
import com.cangchu.document.vo.CustomerListItemVo;
import com.cangchu.document.vo.FollowupReminderVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3 客户跟进场景测试（US-WE-04，architecture/24-p5-c-c3 §6，document 域）。
 *
 * <p>沿用 {@code InquiryScenarioTest} 风格：mapper 直接 seed（tenant/store/wholesaler/userRole/inquiry_requests）
 * + 操控 {@link TenantContext} 模拟 WA/WE 登录态；Job 触发前 clear（系统态无 TenantContext）。
 * 客户 = 本商户按 rt_phone_hmac 归并的询价买家（inquiry_requests 为唯一事实源）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>CF-01 列表归并：同 phone 多单 → 单行计数聚合 + 打码/最近成交；跨商户各成行；WA 只见本商户。</li>
 *   <li>CF-02 备注：写/覆盖/清除（清档）；跨商户互不可见；越权一律 50840。</li>
 *   <li>CF-03 提醒到点：Job 站内信（收件=创建人，正文含打码）+ reminded_at CAS 防重（重跑不重发）。</li>
 *   <li>CF-04 越权/不存在：scope 外 wholesalerId、无角色用户、伪造 key → 50840；删除他人提醒 → 50842。</li>
 *   <li>CF-05 清档规则：删最后一条提醒（无备注）→ 档案清；有备注 → 保留；备注清空后再清档。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class CustomerFollowupScenarioTest {

    @Autowired
    private CustomerFollowupService customerFollowupService;
    @Autowired
    private InquiryRequestMapper inquiryRequestMapper;
    @Autowired
    private CustomerFollowupMapper customerFollowupMapper;
    @Autowired
    private FollowupReminderMapper followupReminderMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed helpers ====================

    private long seedTenant() {
        Tenant t = new Tenant();
        t.setId(snowflakeIdUtil.nextId());
        t.setName("仓-" + t.getId());
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setStatus("ACTIVE");
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhoneCipher(piiCrypto.encrypt("13800000000"));
        tenantMapper.insert(t);
        return t.getId();
    }

    private long seedStore(long tenantId) {
        Store s = new Store();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setName("店-" + s.getId());
        s.setStatus("ACTIVE");
        storeMapper.insert(s);
        return s.getId();
    }

    private long seedWholesaler(long tenantId) {
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("商户-" + w.getId());
        w.setOwnerUserId(snowflakeIdUtil.nextId());
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        return w.getId();
    }

    private long seedWaUser(long tenantId, long wholesalerId) {
        long userId = snowflakeIdUtil.nextId();
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(userId);
        r.setRole("WA");
        r.setTenantId(tenantId);
        r.setWholesalerId(wholesalerId);
        r.setStatus("ACTIVE");
        r.setPriority(5);
        userRoleMapper.insert(r);
        return userId;
    }

    /** seed 询价单（PENDING；confirmedAt 非空时置 CONFIRMED，模拟成交单）。 */
    private void seedInquiry(long tenantId, long wholesalerId, long storeId, String phone, String status) {
        InquiryRequest q = new InquiryRequest();
        q.setId(snowflakeIdUtil.nextId());
        q.setDocNo("XJ-SEED-" + UUID.randomUUID().toString().substring(0, 8));
        q.setStoreId(storeId);
        q.setTenantId(tenantId);
        q.setWholesalerId(wholesalerId);
        q.setStatus(status);
        q.setRtPhoneHmac(piiCrypto.phoneHmac(phone));
        q.setRtPhoneCipher(piiCrypto.encrypt(phone));
        if (InquiryRequest.STATUS_CONFIRMED.equals(status)) {
            q.setConfirmedAt(LocalDateTime.now().minusHours(1));
        }
        inquiryRequestMapper.insert(q);
    }

    private String hmacOf(String phone) {
        return piiCrypto.phoneHmac(phone);
    }

    /** 客户端操作键 = URL-safe Base64(hmac)，与后端 customerKey 同构。 */
    private String keyOf(String phone) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmacOf(phone).getBytes(StandardCharsets.UTF_8));
    }

    private CustomerRemarkDto remark(Long wholesalerId, String remark) {
        CustomerRemarkDto dto = new CustomerRemarkDto();
        dto.setWholesalerId(wholesalerId);
        dto.setRemark(remark);
        return dto;
    }

    private CustomerReminderDto reminder(Long wholesalerId, String content, LocalDateTime remindAt) {
        CustomerReminderDto dto = new CustomerReminderDto();
        dto.setWholesalerId(wholesalerId);
        dto.setContent(content);
        dto.setRemindAt(remindAt);
        return dto;
    }

    private void asWa(long tenantId, long userId) {
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, userId, "WA"));
    }

    private long followupCount(long wholesalerId, String phone) {
        return customerFollowupMapper.selectCount(new LambdaQueryWrapper<CustomerFollowup>()
                .eq(CustomerFollowup::getWholesalerId, wholesalerId)
                .eq(CustomerFollowup::getRtPhoneHmac, hmacOf(phone)));
    }

    // ======================================================================

    @Test
    @DisplayName("CF-01 客户列表归并：同 phone 多单单行 + 跨商户各成行 + WA 只见本商户")
    void cf01_listCustomersAggregates() {
        long tenant = seedTenant();
        long store = seedStore(tenant);
        long widA = seedWholesaler(tenant);
        long widB = seedWholesaler(tenant);
        long waA = seedWaUser(tenant, widA);

        String p1 = "13800006666";
        seedInquiry(tenant, widA, store, p1, InquiryRequest.STATUS_PENDING);
        seedInquiry(tenant, widA, store, p1, InquiryRequest.STATUS_CONFIRMED); // 成交 1
        seedInquiry(tenant, widB, store, p1, InquiryRequest.STATUS_PENDING);   // B 商户独立客户
        String p2 = "13911112222";
        seedInquiry(tenant, widA, store, p2, InquiryRequest.STATUS_PENDING);

        asWa(tenant, waA);
        Page<CustomerListItemVo> page = customerFollowupService.listCustomers(1, 20, waA, tenant);

        assertThat(page.getTotal()).isEqualTo(2); // A 商户下 p1/p2 两客户（B 的不在 scope）
        CustomerListItemVo c1 = page.getRecords().stream()
                .filter(v -> v.getCustomerKey().equals(keyOf(p1))).findFirst().orElseThrow();
        assertThat(c1.getInquiryCount()).isEqualTo(2);
        assertThat(c1.getLastConfirmedAt()).isNotNull();
        assertThat(c1.getMaskedPhone()).isEqualTo(SmsUtil.maskPhone(p1));
        assertThat(c1.getLastInquiryId()).isNotNull();
        assertThat(c1.getWholesalerId()).isEqualTo(widA);
        CustomerListItemVo c2 = page.getRecords().stream()
                .filter(v -> v.getCustomerKey().equals(keyOf(p2))).findFirst().orElseThrow();
        assertThat(c2.getInquiryCount()).isEqualTo(1);
        assertThat(c2.getLastConfirmedAt()).isNull();
    }

    @Test
    @DisplayName("CF-02 备注：写/覆盖/清除（清档）分商户隔离；跨商户与越权一律 50840")
    void cf02_remarkWriteOverwriteClearIsolation() {
        long tenant = seedTenant();
        long store = seedStore(tenant);
        long widA = seedWholesaler(tenant);
        long widB = seedWholesaler(tenant);
        long waA = seedWaUser(tenant, widA);
        long waB = seedWaUser(tenant, widB);

        String p1 = "13800006666";
        seedInquiry(tenant, widA, store, p1, InquiryRequest.STATUS_PENDING);
        seedInquiry(tenant, widB, store, p1, InquiryRequest.STATUS_PENDING); // B 下同买家另有询价

        asWa(tenant, waA);
        customerFollowupService.saveRemark(keyOf(p1), remark(widA, "重点客户-周末回访"), waA, tenant);
        CustomerDetailVo d = customerFollowupService.detailCustomer(keyOf(p1), widA, waA, tenant);
        assertThat(d.getRemark()).isEqualTo("重点客户-周末回访");
        assertThat(d.getInquiryCount()).isEqualTo(1);

        // 覆盖式更新
        customerFollowupService.saveRemark(keyOf(p1), remark(widA, "已回访-待下单"), waA, tenant);
        assertThat(customerFollowupService.detailCustomer(keyOf(p1), widA, waA, tenant).getRemark())
                .isEqualTo("已回访-待下单");

        // B 商户可给「自己的客户」独立建档；A 的备注不可见
        asWa(tenant, waB);
        customerFollowupService.saveRemark(keyOf(p1), remark(widB, "B商户备注"), waB, tenant);
        CustomerDetailVo dB = customerFollowupService.detailCustomer(keyOf(p1), widB, waB, tenant);
        assertThat(dB.getRemark()).isEqualTo("B商户备注");

        // 跨商户越权：waA 改 B 的客户 / waB 改 A 的客户 → 50840（假装不存在）
        BizException ex1 = Assertions.assertThrows(BizException.class, () -> customerFollowupService
                .saveRemark(keyOf(p1), remark(widB, "hack"), waA, tenant));
        assertThat(ex1.getErrorCode()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);
        BizException ex2 = Assertions.assertThrows(BizException.class, () -> customerFollowupService
                .saveRemark(keyOf(p1), remark(widA, "hack"), waB, tenant));
        assertThat(ex2.getErrorCode()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);

        // 清除备注（A 客户无提醒 → 档案清档）
        asWa(tenant, waA);
        customerFollowupService.saveRemark(keyOf(p1), remark(widA, ""), waA, tenant);
        assertThat(followupCount(widA, p1)).isZero();
        assertThat(followupCount(widB, p1)).isEqualTo(1); // B 档案不受影响
    }

    @Test
    @DisplayName("CF-03 提醒到点：Job 发站内信给创建人（正文含打码）+ CAS 防重重跑不重发")
    void cf03_reminderFireDedup() {
        long tenant = seedTenant();
        long store = seedStore(tenant);
        long widA = seedWholesaler(tenant);
        long waA = seedWaUser(tenant, widA);
        String p1 = "13800006666";
        seedInquiry(tenant, widA, store, p1, InquiryRequest.STATUS_CONFIRMED);

        asWa(tenant, waA);
        FollowupReminderVo rvo = customerFollowupService.addReminder(keyOf(p1),
                reminder(widA, "催回款-客户意向强", LocalDateTime.now().plusMinutes(90)), waA, tenant);
        assertThat(customerFollowupService.detailCustomer(keyOf(p1), widA, waA, tenant).getReminders())
                .hasSize(1);
        assertThat(followupCount(widA, p1)).isEqualTo(1); // 建档（含密文冗余）

        // 模拟到点（直接把 remind_at 拨到过去，绕过 addReminder 的未来校验）
        followupReminderMapper.update(null, new LambdaUpdateWrapper<FollowupReminder>()
                .eq(FollowupReminder::getId, rvo.getId())
                .set(FollowupReminder::getRemindAt, LocalDateTime.now().minusMinutes(1)));

        // Job 系统态：无 TenantContext
        TenantContext.clear();
        assertThat(customerFollowupService.fireDueReminders()).isEqualTo(1);

        Notification n = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, Notification.TYPE_CUSTOMER_FOLLOWUP)
                .eq(Notification::getRecipientUserId, waA));
        assertThat(n).isNotNull();
        assertThat(n.getContent()).contains("催回款").contains(SmsUtil.maskPhone(p1));
        assertThat(followupReminderMapper.selectById(rvo.getId()).getRemindedAt()).isNotNull();

        // 重跑：reminded_at 已置位 → 0 触发、通知不重复
        assertThat(customerFollowupService.fireDueReminders()).isZero();
        assertThat(notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, Notification.TYPE_CUSTOMER_FOLLOWUP)
                .eq(Notification::getRecipientUserId, waA))).isEqualTo(1);
    }

    @Test
    @DisplayName("CF-04 越权/不存在：scope 外 wholesalerId / 无角色用户 / 伪造 key → 50840；陌生提醒 → 50842")
    void cf04_forbiddenAndNotFound() {
        long tenant = seedTenant();
        long store = seedStore(tenant);
        long widA = seedWholesaler(tenant);
        long widB = seedWholesaler(tenant);
        long waA = seedWaUser(tenant, widA);
        long waB = seedWaUser(tenant, widB);
        String p1 = "13800006666";
        seedInquiry(tenant, widA, store, p1, InquiryRequest.STATUS_PENDING);

        // scope 外 wholesalerId → 50840
        asWa(tenant, waA);
        BizException ex1 = Assertions.assertThrows(BizException.class,
                () -> customerFollowupService.detailCustomer(keyOf(p1), widB, waA, tenant));
        assertThat(ex1.getErrorCode()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);

        // 伪造 key / 无任何询价 → 50840
        BizException ex2 = Assertions.assertThrows(BizException.class, () -> customerFollowupService
                .detailCustomer("bm90LWEtY3VzdG9tZXI=", widA, waA, tenant)); // base64("not-a-customer")
        assertThat(ex2.getErrorCode()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);

        // 无角色用户：scope 空 → 空列表（不泄露存在性）
        long nobody = snowflakeIdUtil.nextId();
        assertThat(customerFollowupService.listCustomers(1, 20, nobody, tenant).getTotal()).isZero();

        // 同商户内 reminder 不存在 → 50842
        BizException ex3 = Assertions.assertThrows(BizException.class, () -> customerFollowupService
                .deleteReminder(keyOf(p1), widA, snowflakeIdUtil.nextId(), waA, tenant));
        assertThat(ex3.getErrorCode()).isEqualTo(ErrorCode.REMINDER_NOT_FOUND);

        // 越权删除（waB 对 widA 客户操作，先被 scope 收敛）→ 50840
        BizException ex4 = Assertions.assertThrows(BizException.class, () -> customerFollowupService
                .deleteReminder(keyOf(p1), widA, snowflakeIdUtil.nextId(), waB, tenant));
        assertThat(ex4.getErrorCode()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);
    }

    @Test
    @DisplayName("CF-05 清档规则：有备注删最后提醒保留档案；备注清空且无提醒 → 档案清")
    void cf05_cleanupRule() {
        long tenant = seedTenant();
        long store = seedStore(tenant);
        long widA = seedWholesaler(tenant);
        long waA = seedWaUser(tenant, widA);
        String p1 = "13800006666";
        seedInquiry(tenant, widA, store, p1, InquiryRequest.STATUS_PENDING);

        asWa(tenant, waA);
        // 备注 + 提醒并存 → 删最后一条提醒后档案保留（有备注）
        customerFollowupService.saveRemark(keyOf(p1), remark(widA, "有备注"), waA, tenant);
        FollowupReminderVo rvo = customerFollowupService.addReminder(keyOf(p1),
                reminder(widA, "跟进下", LocalDateTime.now().plusHours(1)), waA, tenant);
        customerFollowupService.deleteReminder(keyOf(p1), widA, rvo.getId(), waA, tenant);
        assertThat(followupCount(widA, p1)).isEqualTo(1);
        assertThat(customerFollowupService.detailCustomer(keyOf(p1), widA, waA, tenant).getRemark())
                .isEqualTo("有备注");

        // 备注清空且已无提醒 → 档案清除
        customerFollowupService.saveRemark(keyOf(p1), remark(widA, ""), waA, tenant);
        assertThat(followupCount(widA, p1)).isZero();
        assertThat(followupReminderMapper.selectCount(new LambdaQueryWrapper<FollowupReminder>()
                .eq(FollowupReminder::getWholesalerId, widA))).isZero();

        // 仅提醒无备注 → 删提醒即清档
        FollowupReminderVo only = customerFollowupService.addReminder(keyOf(p1),
                reminder(widA, "仅提醒", LocalDateTime.now().plusHours(2)), waA, tenant);
        assertThat(followupCount(widA, p1)).isEqualTo(1);
        customerFollowupService.deleteReminder(keyOf(p1), widA, only.getId(), waA, tenant);
        assertThat(followupCount(widA, p1)).isZero();
    }
}
