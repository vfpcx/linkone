package com.cangchu.tenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

/**
 * WA 生成批发商员工(WE)注册码入参（P2 入驻 Wave3，契约见 task_plan「接口契约」）。
 *
 * <p>targetRole 固定 WE 不由客户端传入（WEM-S2-01 白名单收敛）；
 * wholesaler_id 由登录 WA 推导（G-2.1 隔离）。permissions ⊆ [PRICE_EDIT, INQUIRY_CONFIRM]，
 * 空/缺省 = 无初始授权（注册后员工只读，可在员工列表再授）。
 */
@Data
public class WholesalerEmployeeInviteCreateDto {

    /** 有效天数（默认 7，>=1） */
    @Min(value = 1, message = "有效天数至少为 1")
    @Max(value = 365, message = "有效天数过大")
    private Integer expireDays;

    /** 最大可用次数（默认 1，>=1） */
    @Min(value = 1, message = "可用次数至少为 1")
    @Max(value = 999, message = "可用次数过大")
    private Integer maxUses;

    /** 初始授权位，白名单 PRICE_EDIT / INQUIRY_CONFIRM（服务层二次校验） */
    private List<String> permissions;
}
