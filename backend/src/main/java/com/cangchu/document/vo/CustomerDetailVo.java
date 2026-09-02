package com.cangchu.document.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** wa 客户详情（C3 · 24-p5-c-c3 §4.2）：列表行字段 + 该客户全部提醒（含已触发历史）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CustomerDetailVo extends CustomerListItemVo {

    private List<FollowupReminderVo> reminders = new ArrayList<>();
}
