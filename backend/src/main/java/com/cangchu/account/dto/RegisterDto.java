package com.cangchu.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求（角色感知）
 *
 * <p>D-16：补齐前端入驻表单字段，避免 Jackson 静默丢弃导致 TA/WA 建仓信息未落库。
 * 字段矩阵（来源 06-page-wireframes §0.5.2 / US-COMMON-01）：
 * <ul>
 *   <li>TA: phone + smsCode + password + realName + tenantName + 营业资质(占位)</li>
 *   <li>WA: phone + smsCode + password + realName + wholesalerName + targetTenantId + 营业资质</li>
 *   <li>WK/ST/WE: phone + smsCode + password + realName + inviteCode</li>
 *   <li>RT: phone + smsCode + agreedTerms（走 /login/rt 免密，自动昵称）</li>
 * </ul>
 */
@Data
public class RegisterDto {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).{6,20}$", message = "密码强度不足（6-20位，含字母数字）")
    private String password;

    @NotBlank(message = "验证码不能为空")
    private String smsCode;

    /** 注册入口角色: TA/WK/ST/WA/WE/RT */
    private String role = "TA";

    /** 邀请码（员工注册场景 WK/ST/WE） */
    private String inviteCode;

    /** 昵称（展示名） */
    private String nickname;

    /** 真实姓名（实名）。非 RT 角色由前端必填；落库到 users.real_name（D-16）。 */
    @Size(max = 64, message = "真实姓名最多 64 字")
    private String realName;

    /** 仓库名称。TA 注册建仓用，创建 PENDING 租户壳（D-16）。 */
    @Size(max = 128, message = "仓库名称最多 128 字")
    private String tenantName;

    /** 商户名称。WA 入驻用（待 WA 入驻模块落地，当前仅接收+校验，见交付待决点）。 */
    @Size(max = 128, message = "商户名称最多 128 字")
    private String wholesalerName;

    /** 目标仓库 ID。WA 选择入驻的租户（雪花 ID 字符串）。 */
    private String targetTenantId;

    /**
     * 是否已同意用户协议/隐私政策。
     * D-16 / G-3.1：必须为 true 才放行注册，否则服务层抛 40001。
     * 用 Boolean 包装类型以区分「未传(null)」与「显式 false」，二者均拒绝。
     */
    private Boolean agreedTerms;
}
