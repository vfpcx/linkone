package com.cangchu.common.exception;

import lombok.Getter;

/**
 * 统一错误码枚举（基于架构文档 05-error-codes.md）
 */
@Getter
public enum ErrorCode {

    // ==================== VALIDATION (40000-40999) ====================
    VALIDATION_BASIC_001(40001, "参数校验失败"),
    VALIDATION_BASIC_002(40002, "请求体格式错误"),
    VALIDATION_BASIC_003(40003, "缺少必填参数"),
    VALIDATION_FORMAT_001(40101, "手机号格式不正确"),
    VALIDATION_FORMAT_002(40102, "密码强度不足（6-20位，含字母数字）"),
    VALIDATION_BUSINESS_001(40201, "数量必须大于0"),

    // ==================== AUTH (41000-41999) ====================
    AUTH_BASIC_001(41001, "您尚未登录，请先登录"),
    AUTH_BASIC_002(41002, "登录已过期，请重新登录"),
    AUTH_BASIC_003(41003, "您的账号已在其他设备登录"),
    AUTH_BASIC_004(41004, "账号已被冻结，请联系平台"),
    AUTH_BASIC_005(41005, "Token无效"),

    AUTH_ACCOUNT_001(41101, "账号或密码错误"),
    AUTH_ACCOUNT_002(41102, "账号已锁定，请30分钟后重试"),
    AUTH_ACCOUNT_003(41103, "手机号未注册"),
    AUTH_ACCOUNT_004(41104, "该手机号已注册，请直接登录"),
    AUTH_ACCOUNT_005(41105, "新密码与旧密码相同"),
    AUTH_ACCOUNT_006(41106, "新密码不能与最近3次密码相同"),
    AUTH_ACCOUNT_007(41107, "旧密码错误"),

    AUTH_SMS_001(41201, "验证码已过期，请重新获取"),
    AUTH_SMS_002(41202, "验证码错误"),
    AUTH_SMS_003(41203, "验证码错误次数过多，请15分钟后重试"),
    AUTH_SMS_004(41204, "请60秒后再获取验证码"),
    AUTH_SMS_005(41205, "今日验证码次数已达上限"),
    AUTH_SMS_006(41206, "验证码尚未获取，请先获取"),

    AUTH_INVITE_001(41301, "邀请码无效"),
    AUTH_INVITE_002(41302, "邀请码已过期"),
    AUTH_INVITE_003(41303, "邀请码已用完"),
    AUTH_INVITE_004(41304, "邀请码与目标角色不匹配"),

    // ==================== PERMISSION (42000-42999) ====================
    PERMISSION_ROLE_001(42001, "您没有此操作的权限"),
    PERMISSION_ROLE_002(42002, "OPS平台操作仅限OPS角色"),
    PERMISSION_TENANT_001(42101, "您没有访问此租户数据的权限"),
    PERMISSION_TENANT_002(42102, "数据隔离异常，请联系管理员"),

    // ==================== LIMIT (43000-43999) ====================
    LIMIT_RATE_001(43001, "操作过于频繁，请稍后再试"),
    LIMIT_SMS_001(43003, "短信发送频率受限，请稍后"),

    // ==================== STATE (50000-50999) ====================
    STATE_TENANT_001(50101, "租户审核中，暂不可操作"),
    STATE_TENANT_002(50102, "租户已冻结，所有操作受限"),
    STATE_TENANT_003(50103, "租户已下线"),

    // ==================== BUSINESS (60000-69999) ====================
    BUSINESS_INVENTORY_001(60001, "库存不足"),

    // ==================== SYSTEM (90000-99999) ====================
    SYSTEM_INTERNAL_001(90001, "系统繁忙，请稍后再试"),
    SYSTEM_INTERNAL_002(90002, "数据库异常"),

    // ==================== 自定义扩展 ====================
    ACCOUNT_PASSWORD_WEAK(41108, "密码必须包含数字和字母"),
    ACCOUNT_OLD_PASSWORD_WRONG(41109, "原密码错误"),
    SMS_CODE_NOT_FOUND(41207, "请先获取验证码"),
    SMS_CODE_VERIFY_EXCEED(41208, "验证码验证次数超限"),
    PERMISSION_LOGIN_FAILED(42006, "登录失败次数过多，账号已锁定"),
    TENANT_NOT_FOUND(50210, "租户不存在"),
    TENANT_ALREADY_EXISTS(50211, "该手机号已注册租户"),
    CAPACITY_NOT_FOUND(50220, "容量快照不存在"),
    WHOLESALER_NOT_FOUND(50230, "批发商商户不存在"),
    WHOLESALER_NAME_DUPLICATED(50231, "本租户下已存在同名批发商商户"),

    // ==================== PRODUCT / SKU (phase-1 A2) ====================
    SKU_NOT_FOUND(50240, "商品 SKU 不存在"),
    SKU_PRICE_INVALID(50241, "商品价格非法（单价>0，起批价>=0，起批量>=1）"),
    SKU_NAME_REQUIRED(50242, "商品名称不能为空");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
