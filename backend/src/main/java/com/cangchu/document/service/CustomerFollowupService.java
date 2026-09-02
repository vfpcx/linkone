package com.cangchu.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.CustomerReminderDto;
import com.cangchu.document.dto.CustomerRemarkDto;
import com.cangchu.document.vo.CustomerDetailVo;
import com.cangchu.document.vo.CustomerListItemVo;
import com.cangchu.document.vo.FollowupReminderVo;

/**
 * wa 客户跟进（C3 · US-WE-04，24-p5-c-c3 §4，document 域）。
 *
 * <p>客户 = 当前租户下登录人归属 wholesaler 的询价买家（按 rt_phone_hmac 归并）；
 * 操作键 customerKey = URL-safe Base64(hmac)；wholesaler 收敛（K-7）：不在 scope → 50840 假装不存在。
 */
public interface CustomerFollowupService {

    /** 客户列表（当前租户 + 登录人 wholesaler scope；分页，页内按最近询价倒序）。 */
    Page<CustomerListItemVo> listCustomers(int page, int size, Long userId, Long tenantId);

    /** 客户详情：统计 + 档案备注 + 全部提醒（含已触发历史）。 */
    CustomerDetailVo detailCustomer(String customerKey, Long wholesalerId, Long userId, Long tenantId);

    /** 备注覆盖保存（remark 空串=清除备注，无提醒则清档，K-3）。 */
    void saveRemark(String customerKey, CustomerRemarkDto dto, Long userId, Long tenantId);

    /** 新建跟进提醒（无档案自动建档；remindAt 须晚于 now → 否则 50841）。 */
    FollowupReminderVo addReminder(String customerKey, CustomerReminderDto dto, Long userId, Long tenantId);

    /** 删除跟进提醒（同商户 WE/WA 共治；删除后清档规则同 saveRemark）。 */
    void deleteReminder(String customerKey, Long wholesalerId, Long reminderId, Long userId, Long tenantId);

    /** Job：到点提醒触发站内信（reminded_at CAS 防重）；返回本次触发条数。 */
    int fireDueReminders();
}
