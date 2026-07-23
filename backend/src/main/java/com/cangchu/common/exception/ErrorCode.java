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
    // 42004 落地 05-error-codes.md 既有预留（WE 授权位不足，P2 入驻 Wave3）
    PERMISSION_ROLE_004(42004, "WE 角色无此权限，请联系 WA"),
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
    SKU_NAME_REQUIRED(50242, "商品名称不能为空"),

    // ==================== INVENTORY (phase-1 B1) ====================
    INVENTORY_NOT_FOUND(50250, "库存记录不存在"),
    STOCK_NOT_ENOUGH(50251, "库存不足，无法出库"),
    STOCK_QTY_INVALID(50252, "出入库数量非法（必须>0）"),
    INVENTORY_LOCK_FAILED(50253, "库存繁忙，请稍后重试"),

    // ==================== STORE-FRONT (phase-1 B2) ====================
    STORE_NOT_FOUND(50260, "店铺不存在或已下线"),

    // ==================== INBOUND / DOCUMENT (phase-1 C1) ====================
    INBOUND_QTY_INVALID(50270, "入库数量非法（必须>0）"),
    INBOUND_WHOLESALER_REQUIRED(50271, "缺少批发商商户"),
    INBOUND_NOT_FOUND(50272, "入库单不存在"),
    INBOUND_OPERATOR_NOT_WK(50273, "仅本租户仓管(WK)可登记入库"),
    DOC_NO_GENERATE_FAILED(50274, "单据号生成失败，请稍后重试"),

    // ==================== INQUIRY / OUTBOUND (phase-1 C2) ====================
    INQUIRY_ITEMS_REQUIRED(50280, "询价商品不能为空"),
    INQUIRY_QTY_INVALID(50281, "询价数量非法（必须>0）"),
    INQUIRY_WHOLESALER_NOT_IN_STORE(50282, "批发商不属于该店铺或未上架"),
    INQUIRY_SKU_NOT_BELONG(50283, "商品不属于该批发商"),
    INQUIRY_NOT_FOUND(50284, "询价单不存在"),
    INQUIRY_STATUS_INVALID(50285, "询价单状态不允许此操作"),
    INQUIRY_OPERATOR_NOT_WA(50286, "仅该批发商(WA)可确认询价"),
    OUTBOUND_GENERATE_FAILED(50287, "出库单生成失败，请稍后重试"),

    // ==================== EMPLOYEE INVITE (phase-1 员工注册码：解锁 WK 入库) ====================
    // 凭码注册时的码校验复用 AUTH_INVITE_001..004（41301-41304）；以下为 TA 生码/管理侧补充错误码。
    INVITE_ROLE_NOT_ALLOWED(50290, "员工注册码角色仅允许 WK 或 ST"),
    INVITE_CODE_NOT_FOUND(50291, "员工注册码不存在"),
    INVITE_CODE_REVOKED(50292, "员工注册码已作废"),

    // ==================== PRICING (P2 定价 Wave 1) ====================
    CUSTOMER_PRICE_NOT_FOUND(50300, "客户专属价不存在"),
    CUSTOMER_PRICE_INVALID(50301, "专属价必须大于0"),
    PRICE_MATCH_FAILED(50302, "价格匹配失败，请稍后重试"),

    // ==================== PRICING 批量调价 (P2 定价 Wave 2) ====================
    PRICE_BATCH_TOO_FREQUENT(50303, "调价操作过于频繁，请5分钟后再试"),
    PRICE_BATCH_MODE_INVALID(50304, "调价方式非法或不适用于该价格类型"),
    PRICE_BATCH_TARGET_REQUIRED(50305, "批量调价目标为空或参数缺失"),
    PRICE_BATCH_LOCK_FAILED(50306, "调价繁忙，请稍后重试"),

    // ==================== ONBOARDING 入驻主链 (P2 入驻 Wave 1) ====================
    // 落地 05-error-codes.md STATE_WHOLESALER 预留段 50201-50205（决策 O-3）；溢出用 50310+。
    WHOLESALER_APPLICATION_PENDING(50201, "批发商入驻审核中，请勿重复申请"),
    WHOLESALER_WITHDRAWN(50202, "批发商已退驻"),
    WHOLESALER_APPLICATION_NOT_AUDITABLE(50203, "入驻申请不存在或当前状态不可审核"),
    WHOLESALER_ALREADY_ONBOARDED(50204, "该账号已入驻批发商，一个账号仅可入驻一个仓库"),
    // DEF-2（Wave6）：对外文案中性化，不透出「黑名单」字样（测试计划硬要求）；语义仍为黑名单拦截
    BLACKLIST_HIT(50205, "暂不满足入驻条件，请联系平台客服"),

    // ==================== BLACKLIST 管理 (P2 入驻 Wave 1，O-3 溢出段 50310+) ====================
    BLACKLIST_ENTRY_EXISTS(50310, "黑名单条目已存在"),
    BLACKLIST_ENTRY_NOT_FOUND(50311, "黑名单条目不存在"),

    // ==================== R13 退驻 / R14 强制下架 (P2 入驻 Wave 2，50312+) ====================
    WITHDRAW_STOCK_NOT_ZERO(50312, "退驻前须清空库存（当前仍有在库商品）"),
    WHOLESALER_NOT_ACTIVE(50313, "该批发商已下架或退驻，无法受理新业务"),
    WITHDRAW_OPEN_DOCS_EXIST(50314, "退驻前须结清单据（存在未完结的询价/出库单）"),
    WITHDRAW_APPLICATION_NOT_AUDITABLE(50315, "退驻申请不存在或当前状态不可审核"),
    WITHDRAW_APPLICATION_PENDING(50316, "已有退驻申请审核中，请勿重复提交"),
    WITHDRAW_RESTORE_EXPIRED(50317, "退驻恢复窗口已过（超 60 天或已归档）"),
    WHOLESALER_STATE_TRANSITION_INVALID(50318, "批发商当前状态不允许该操作"),

    // ==================== WE 批发商员工 (P2 入驻 Wave 3，50319+) ====================
    EMPLOYEE_INVITE_PERMISSION_INVALID(50319, "员工授权项非法（仅允许 PRICE_EDIT/INQUIRY_CONFIRM）"),
    EMPLOYEE_NOT_FOUND(50320, "员工不存在或不属于本商户"),
    EMPLOYEE_STATE_INVALID(50321, "员工当前状态不允许该操作"),
    EMPLOYEE_RESTORE_EXPIRED(50322, "员工禁用已超 30 天，无法恢复"),

    // WEM-S5-01：账号存在但全部角色被禁用（如被 R17 禁用的 WE）→ 登录给语义提示而非兜底 TA
    ACCOUNT_ALL_ROLES_DISABLED(41110, "账号已被禁用，请联系商户管理员");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
