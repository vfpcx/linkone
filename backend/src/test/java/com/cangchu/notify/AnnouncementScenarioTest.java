package com.cangchu.notify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.TestUniq;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.notify.dto.AnnouncementCreateDto;
import com.cangchu.notify.entity.Announcement;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.AnnouncementMapper;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.notify.service.AnnouncementService;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.notify.vo.AnnouncementVo;
import com.cangchu.notify.vo.NotificationVo;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5-A W3（18-p5-design §4.2/§4.4）：平台公告发布收件人推导 + 通知中心增强（分组/全部已读）。
 *
 * <p>关键验证：① target_roles 展开以 user_roles(role, ACTIVE) 为唯一可信来源（多 WA 账号全发）；
 * ② ALL=全平台 ACTIVE；③ 状态机 DRAFT→PUBLISHED→INACTIVE（重复发布 50502）；④ 非 OPS → 42002；
 * ⑤ 通知中心 group=ANNOUNCE/BIZ 分组筛选 + readAll 幂等（18 §6：弹窗去重复用 readAt，零新设施）。
 */
@SpringBootTest(classes = CangchuApplication.class)
class AnnouncementScenarioTest {

    @Autowired
    private AnnouncementService announcementService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private AnnouncementMapper announcementMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed ====================

    private record Ctx(long tenantId, long taUserId, long wkUserId, long wholesalerId,
                       long wa1UserId, long wa2UserId, long weUserId, long opsUserId) {
    }

    private Ctx seed() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = seedRole(null, "TA", tenantId, null);
        long wkUserId = seedRole(null, "WK", tenantId, null);
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhoneCipher(piiCrypto.encrypt("1" + String.format("%010d", tenantId % 10_000_000_000L)));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);

        long wholesalerId = snowflakeIdUtil.nextId();
        Wholesaler w = new Wholesaler();
        w.setId(wholesalerId);
        w.setTenantId(tenantId);
        w.setName("商户-" + wholesalerId);
        w.setOwnerUserId(taUserId);
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);

        long wa1 = seedRole(null, "WA", tenantId, wholesalerId);
        long wa2 = seedRole(null, "WA", tenantId, wholesalerId);
        long we = seedRole(null, "WE", tenantId, wholesalerId);
        long ops = seedRole(null, "OPS", null, null);
        return new Ctx(tenantId, taUserId, wkUserId, wholesalerId, wa1, wa2, we, ops);
    }

    private long seedRole(Long userId, String role, Long tenantId, Long wholesalerId) {
        long uid = userId != null ? userId : snowflakeIdUtil.nextId();
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(uid);
        r.setRole(role);
        r.setTenantId(tenantId);
        r.setWholesalerId(wholesalerId);
        r.setStatus("ACTIVE");
        r.setPriority(3);
        userRoleMapper.insert(r);
        return uid;
    }

    private AnnouncementCreateDto dto(List<String> targetRoles) {
        AnnouncementCreateDto d = new AnnouncementCreateDto();
        d.setTitle("平台公告");
        d.setContent("系统将于本周日 02:00-04:00 升级维护，请提前安排作业。");
        d.setTargetRoles(targetRoles);
        return d;
    }

    private long countAnnouncementNotifications(long recipient) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, recipient)
                .eq(Notification::getType, Notification.TYPE_PLATFORM_ANNOUNCEMENT));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("WA_WE 公告 → 目标角色（多 WA 账号全发）收到，WK/TA 对照未收")
    void publish_waWe_group_deliversToWaAndWeOnly() {
        Ctx c = seed();
        Long id = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_WA_WE)));
        announcementService.publish(c.opsUserId(), id);

        assertThat(countAnnouncementNotifications(c.wa1UserId())).isEqualTo(1);
        assertThat(countAnnouncementNotifications(c.wa2UserId())).isEqualTo(1);
        assertThat(countAnnouncementNotifications(c.weUserId())).isEqualTo(1);
        assertThat(countAnnouncementNotifications(c.wkUserId())).isZero();
        assertThat(countAnnouncementNotifications(c.taUserId())).isZero();

        Announcement stored = announcementMapper.selectById(id);
        assertThat(stored.getStatus()).isEqualTo(Announcement.STATUS_PUBLISHED);
        assertThat(stored.getPublishedBy()).isEqualTo(c.opsUserId());
        assertThat(stored.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("ALL 公告 → 全平台 ACTIVE 用户均收到（含 TA/WK）")
    void publish_all_deliversToEveryActiveUser() {
        Ctx c = seed();
        Long id = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_ALL)));
        announcementService.publish(c.opsUserId(), id);

        assertThat(countAnnouncementNotifications(c.taUserId())).isEqualTo(1);
        assertThat(countAnnouncementNotifications(c.wkUserId())).isEqualTo(1);
        assertThat(countAnnouncementNotifications(c.wa1UserId())).isEqualTo(1);
        assertThat(countAnnouncementNotifications(c.wa2UserId())).isEqualTo(1);
        assertThat(countAnnouncementNotifications(c.weUserId())).isEqualTo(1);
        // 发布人（OPS）也在 ALL 收件人内（业务语义：全员通知含运营）
        assertThat(countAnnouncementNotifications(c.opsUserId())).isEqualTo(1);
    }

    @Test
    @DisplayName("重复发布 / 非 OPS 创建 / 非法角色组 → 语义错误码")
    void publish_rejectsInvalidStatesAndPermissions() {
        Ctx c = seed();

        // 非 OPS（TA）创建 → 42002
        assertThatThrownBy(() -> announcementService.create(c.taUserId(), dto(List.of(Announcement.GROUP_ALL))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_002.getCode()));

        // OPS 正常创建并发布
        Long id = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_OPS)));
        announcementService.publish(c.opsUserId(), id);

        // 重复发布 → 50502
        assertThatThrownBy(() -> announcementService.publish(c.opsUserId(), id))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.ANNOUNCEMENT_STATE_INVALID.getCode()));

        // 非法角色组 → 50503
        assertThatThrownBy(() -> announcementService.create(c.opsUserId(), dto(List.of("HACKER"))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.ANNOUNCEMENT_TARGET_ROLES_INVALID.getCode()));
    }

    @Test
    @DisplayName("下架：PUBLISHED→INACTIVE，已发通知保留")
    void inactivate_keepsSentNotifications() {
        Ctx c = seed();
        Long id = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_WA_WE)));
        announcementService.publish(c.opsUserId(), id);
        announcementService.inactivate(c.opsUserId(), id);

        Announcement stored = announcementMapper.selectById(id);
        assertThat(stored.getStatus()).isEqualTo(Announcement.STATUS_INACTIVE);
        // 已发通知不删（通知中心常驻）
        assertThat(countAnnouncementNotifications(c.wa1UserId())).isEqualTo(1);
        // 草稿不可直接下架 → 50502
        Long draftId = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_OPS)));
        assertThatThrownBy(() -> announcementService.inactivate(c.opsUserId(), draftId))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.ANNOUNCEMENT_STATE_INVALID.getCode()));
    }

    @Test
    @DisplayName("通知中心：ANNOUNCE/BIZ 分组筛选 + readAll 幂等")
    void notificationCenter_groupAndReadAll() {
        Ctx c = seed();
        Long id = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_ALL)));
        announcementService.publish(c.opsUserId(), id);

        // group=ANNOUNCE 仅公告；group=BIZ 不含公告；缺省=全部
        Page<NotificationVo> announce = notificationService.listMine(c.wa1UserId(), 1, 20, false, "ANNOUNCE");
        assertThat(announce.getRecords()).hasSize(1);
        assertThat(announce.getRecords().get(0).getType()).isEqualTo(Notification.TYPE_PLATFORM_ANNOUNCEMENT);

        Page<NotificationVo> biz = notificationService.listMine(c.wa1UserId(), 1, 20, false, "BIZ");
        assertThat(biz.getRecords()).isEmpty();

        // unreadOnly=true 时公告未读可见；readAll 后全部已读
        Page<NotificationVo> unread = notificationService.listMine(c.wa1UserId(), 1, 20, true, "ANNOUNCE");
        assertThat(unread.getRecords()).hasSize(1);

        notificationService.readAll(c.wa1UserId());
        notificationService.readAll(c.wa1UserId()); // 幂等

        Page<NotificationVo> after = notificationService.listMine(c.wa1UserId(), 1, 20, true, "ANNOUNCE");
        assertThat(after.getRecords()).isEmpty();

        // 非法 group → 参数校验错误
        assertThatThrownBy(() -> notificationService.listMine(c.wa1UserId(), 1, 20, false, "XXX"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_003.getCode()));
    }

    @Test
    @DisplayName("回归（E2E 全链实证）：登录态 TA（带 TenantContext）可见平台公告 tenant_id=null 站内信")
    void platformAnnouncement_visibleUnderTenantContext() {
        Ctx c = seed();
        Long id = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_TA)));
        announcementService.publish(c.opsUserId(), id);

        // 模拟 TA 真实登录态（TenantContext 注入后 TenantLine 生效）：
        // 平台级公告站内信 tenant_id=null，不应被 `tenant_id = ?` 行级过滤隐藏
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
        Page<NotificationVo> announce = notificationService.listMine(c.taUserId(), 1, 20, false, "ANNOUNCE");
        assertThat(announce.getRecords())
                .as("登录态 TA 可见平台公告站内信")
                .extracting(NotificationVo::getType)
                .contains(Notification.TYPE_PLATFORM_ANNOUNCEMENT);
        assertThat(notificationService.unreadCount(c.taUserId())).isGreaterThan(0);

        // 本人 scope 不因租户过滤放开而改变：非目标角色（WK）仍不可见
        Page<NotificationVo> other = notificationService.listMine(c.wkUserId(), 1, 20, false, "ANNOUNCE");
        assertThat(other.getRecords()).isEmpty();
    }

    @Test
    @DisplayName("公告列表/详情：OPS 可分页查询并按状态过滤")
    void pageAndDetail_opsOnly() {
        Ctx c = seed();
        Long draftId = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_TA)));
        Long secondDraftId = announcementService.create(c.opsUserId(), dto(List.of(Announcement.GROUP_WK_ST)));

        // 平台级表无租户隔离，H2 用例间共享——断言「包含本次创建」而非精确全量
        Page<AnnouncementVo> all = announcementService.page(c.opsUserId(), 1, 100, null);
        assertThat(all.getRecords().stream().map(AnnouncementVo::getId)).contains(draftId, secondDraftId);

        Page<AnnouncementVo> drafts = announcementService.page(c.opsUserId(), 1, 100, Announcement.STATUS_DRAFT);
        assertThat(drafts.getRecords().stream().map(AnnouncementVo::getId)).contains(draftId, secondDraftId);

        AnnouncementVo detail = announcementService.detail(c.opsUserId(), draftId);
        assertThat(detail.getTargetRoles()).containsExactly(Announcement.GROUP_TA);

        // 非 OPS 访问 → 42002
        assertThatThrownBy(() -> announcementService.page(c.taUserId(), 1, 20, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_002.getCode()));
    }
}
