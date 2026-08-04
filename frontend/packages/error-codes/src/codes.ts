/**
 * 仓储云 · 错误码 enum（前端可处理子集，覆盖 30+ 高频错误码）
 *
 * 对齐 shared/architecture/05-error-codes.md
 *
 * 七大类数值段（前端按段路由）：
 *  - 41xxx · AUTH       鉴权失败 → 清 token 跳登录
 *  - 42xxx · PERMISSION 权限不足 → Toast「无权限」
 *  - 43xxx · LIMIT      限流 → Toast + Retry-After
 *  - 40xxx · VALIDATION 校验失败 → 字段红边 + Toast
 *  - 50xxx · STATE      状态机错误 → Toast「状态不可达」
 *  - 60xxx · BUSINESS   业务规则 → Toast 友好提示
 *  - 9xxxx · SYSTEM     系统错误 → Toast「系统繁忙」+ trace_id
 */

export enum ErrorCode {
  // ============ 成功 ============
  OK = 0,

  // ============ AUTH (41xxx) ============
  AUTH_TOKEN_MISSING = 41001,
  AUTH_TOKEN_EXPIRED = 41002,
  AUTH_TOKEN_KICKED = 41003,
  AUTH_USER_FROZEN = 41004,
  AUTH_TOKEN_INVALID = 41005,

  AUTH_INVALID_CREDENTIALS = 41101,
  AUTH_ACCOUNT_LOCKED = 41102,
  AUTH_PHONE_NOT_REGISTERED = 41103,
  AUTH_PHONE_ALREADY_REGISTERED = 41104,
  AUTH_NEW_PASSWORD_SAME = 41105,
  AUTH_NEW_PASSWORD_HISTORY = 41106,
  AUTH_OLD_PASSWORD_WRONG = 41107,
  /** R17 · 全部角色被禁用，登录拒绝（WE 被商户禁用后） */
  AUTH_ALL_ROLES_DISABLED = 41110,

  AUTH_SMS_EXPIRED = 41201,
  AUTH_SMS_WRONG = 41202,
  AUTH_SMS_LOCKOUT = 41203,
  AUTH_SMS_INTERVAL = 41204,
  AUTH_SMS_DAILY_LIMIT = 41205,
  AUTH_SMS_NOT_FOUND = 41206,

  AUTH_INVITE_INVALID = 41301,
  AUTH_INVITE_EXPIRED = 41302,
  AUTH_INVITE_EXHAUSTED = 41303,
  AUTH_INVITE_ROLE_MISMATCH = 41304,

  // ============ PERMISSION (42xxx) ============
  PERMISSION_ROLE_DENIED = 42001,
  PERMISSION_OPS_ONLY = 42002,
  PERMISSION_TA_ONLY = 42003,
  PERMISSION_WE_LIMITED = 42004,
  PERMISSION_CROSS_STORE = 42005,

  PERMISSION_CROSS_TENANT = 42101,
  PERMISSION_TENANT_LEAK = 42102,
  PERMISSION_WA_NOT_IN_TENANT = 42103,

  PERMISSION_NOT_OWNER = 42201,
  PERMISSION_SKU_OWNERSHIP = 42202,
  PERMISSION_INQUIRY_RECIPIENT = 42203,

  // ============ LIMIT (43xxx) ============
  LIMIT_RATE = 43001,
  LIMIT_QUOTA = 43002,
  LIMIT_SMS = 43003,
  LIMIT_ASR = 43004,

  // ============ VALIDATION (40xxx) ============
  VALIDATION_FAILED = 40001,
  VALIDATION_JSON = 40002,
  VALIDATION_REQUIRED = 40003,
  VALIDATION_RANGE = 40004,

  VALIDATION_PHONE_FORMAT = 40101,
  VALIDATION_PASSWORD_WEAK = 40102,
  VALIDATION_AMOUNT_FORMAT = 40103,
  VALIDATION_DATE_FORMAT = 40104,
  VALIDATION_COORD_RANGE = 40105,
  VALIDATION_FILE_SIZE = 40106,
  VALIDATION_FILE_TYPE = 40107,

  VALIDATION_QTY_POSITIVE = 40201,
  VALIDATION_QTY_BELOW_THRESHOLD = 40202,
  VALIDATION_PRICE_POSITIVE = 40203,
  VALIDATION_DISCOUNT_EXCEEDS = 40204,
  VALIDATION_PROD_DATE_FUTURE = 40205,
  VALIDATION_EXPIRY_BEFORE_PROD = 40206,
  VALIDATION_DUPLICATE_CUSTOMER_PRICE = 40207,

  // ============ STATE (50xxx) ============
  STATE_DOC_CHANGED = 50001,
  STATE_DOC_INVALID_TRANSITION = 50002,
  STATE_INBOUND_REGISTERED = 50003,
  STATE_PROXY_OUTBOUND_NO_DISPUTE = 50004,
  STATE_INQUIRY_CONFIRMED = 50005,
  STATE_INQUIRY_EXPIRED = 50006,
  STATE_AUTO_CONFIRMED_72H = 50007,
  STATE_UNDER_ARBITRATION = 50008,

  STATE_TENANT_PENDING = 50101,
  STATE_TENANT_FROZEN = 50102,
  STATE_TENANT_OFFLINE = 50103,

  STATE_WA_PENDING = 50201,
  STATE_WA_WITHDRAWN = 50202,
  /** 申请不存在或状态不可审（含跨租户不可见、并发审批 CAS 抢占失败）· 10-onboarding-design.md §3/§6 重定义 */
  STATE_WA_APPLICATION_NOT_AUDITABLE = 50203,
  /** 重复入驻（一个 WA 账号仅一个 ACTIVE 入驻）· 10-onboarding-design.md §3/§6 重定义 */
  STATE_WA_ALREADY_ONBOARDED = 50204,
  STATE_WA_BLACKLISTED = 50205,

  // 员工注册码已作废（TA 主动作废 / phase-1 EmployeeInvite REVOKED）
  STATE_INVITE_REVOKED = 50292,

  // 黑名单管理（P2 入驻生态 · 10-onboarding-design.md §6 溢出段；
  // 数值落在 50310-50329 段但语义属黑名单，不会出现在退驻页面）
  /** 黑名单条目已存在（重复加黑） */
  STATE_BLACKLIST_DUPLICATE = 50310,
  /** 黑名单条目不存在 */
  STATE_BLACKLIST_NOT_FOUND = 50311,

  // R13 退驻 / R14 强制下架（P2 入驻生态 · 50310-50329 段为入驻扩展）
  // 权威来源：10-onboarding-design.md §11-§14；前端按 isWithdrawPreconditionFailed 段判断回填自查清单
  /** 退驻前置不满足：库存未清零 */
  STATE_WITHDRAW_STOCK_NOT_CLEARED = 50312,
  /** 商户非 ACTIVE，新业务（新询价/确认 PENDING 询价等）拒绝 */
  STATE_WA_NOT_ACTIVE = 50313,
  /** 退驻前置不满足：存在未结单据 */
  STATE_WITHDRAW_OPEN_DOCS = 50314,
  /** 退驻申请不可审/不可撤（已被审批、CAS 竞争失败） */
  STATE_WITHDRAW_NOT_AUDITABLE = 50315,
  /** 重复退驻申请（已有 PENDING 在审） */
  STATE_WITHDRAW_DUPLICATE = 50316,
  /** 60 天恢复窗口已过（已归档） */
  STATE_RESTORE_WINDOW_EXPIRED = 50317,
  /** 商户状态机不可达转移（如 OFFLINE→ACTIVE） */
  STATE_WHOLESALER_TRANSITION_INVALID = 50318,

  // WE 员工管理（P2 入驻生态 Wave3 · 50319-50322；
  // 仅出现在 /wholesaler/employees* 端点，不会出现在退驻页面）
  /** 授权项非法（permissions ⊄ [PRICE_EDIT, INQUIRY_CONFIRM]） */
  STATE_EMPLOYEE_PERMISSION_INVALID = 50319,
  /** 员工不存在或不属本商户 */
  STATE_EMPLOYEE_NOT_FOUND = 50320,
  /** 员工状态不允许该操作（含重复禁用 / 恢复未禁用员工） */
  STATE_EMPLOYEE_STATUS_INVALID = 50321,
  /** 恢复已逾 30 天窗口（员工已永久移除） */
  STATE_EMPLOYEE_RESTORE_EXPIRED = 50322,

  // P3 单据异常链（50330-50349 段 · 05-error-codes.md P3 BE-W1 登记；
  // 权威来源：12-p3-design.md §6.2；50335-50339 为 BE-W2 预留，先行登记）
  /** 状态机不可达兜底（迁移矩阵 12 §1.2/§2.1） */
  STATE_DOC_TRANSITION_INVALID = 50330,
  /** CAS 抢占失败（并发被抢占/重复提交） */
  STATE_DOC_CAS_CONFLICT = 50331,
  /** 72h 确认窗口已关（超窗 confirm/dispute，单据已自动确认） */
  STATE_INBOUND_CONFIRM_WINDOW_CLOSED = 50332,
  /** 结论选项与仲裁类型不符 / REJECTED 缺 remark */
  STATE_ARBITRATION_CONCLUSION_INVALID = 50333,
  /** 仲裁单不存在/已裁决（跨租户按不存在，不泄漏存在性） */
  STATE_ARBITRATION_NOT_PENDING = 50334,
  /** R4 当前状态不可撤回（BE-W2 启用） */
  STATE_OUTBOUND_NOT_WITHDRAWABLE = 50335,
  /** 该单无待确认撤回申请（BE-W2 启用） */
  STATE_OUTBOUND_NO_WITHDRAW_REQUEST = 50336,
  /** R8 意向单不可作废（BE-W2 启用） */
  STATE_INQUIRY_NOT_VOIDABLE = 50337,
  /** 大额出库需复述件数确认（BE-W2 启用） */
  STATE_OUTBOUND_LARGE_CONFIRM_REQUIRED = 50338,
  /** 客诉期已过（BE-W2 启用） */
  STATE_OUTBOUND_COMPLAINT_WINDOW_CLOSED = 50339,
  /** 上传文件格式/大小不符（jpg/png/webp ≤5MB，魔数校验） */
  STATE_FILE_UPLOAD_INVALID = 50340,
  /** 消息不存在（已读非本人/不存在按不存在） */
  STATE_NOTIFICATION_NOT_FOUND = 50341,
  /** 差额定责三态校验失败（REJECTED∧shortfall>0 必填；其余必空） */
  STATE_ARBITRATION_LIABILITY_INVALID = 50342,

  // P3b T1 正向申请链（50350-50369 段 · 13-p3b-design.md §4.2，D-14 溢出段；
  // 50343-50349 维持预留缓冲；权威来源：backend ErrorCode.java 实测）
  /** R1 撤回失败：仅待受理可撤，受理锁单后须走库管员流转 */
  STATE_INBOUND_NOT_WITHDRAWABLE = 50350,
  /** T1-5 登记差异边界：|实登−申请|/申请 > 5%，须驳回后重新申请 */
  STATE_INBOUND_QTY_DIFF_EXCEEDED = 50351,
  /** R3 纠错超窗：登记已超 24 小时（数据库时间比对） */
  STATE_INBOUND_CORRECTION_WINDOW_CLOSED = 50352,
  /** R3 防重：同单已有待审批纠错申请 */
  STATE_INBOUND_CORRECTION_PENDING_EXISTS = 50353,
  /** R3 件数非法：为负 / 与实登相同 / 非正向链或非已入库单据 */
  STATE_INBOUND_CORRECTION_INVALID = 50354,

  // P3b T3 退货 / 盘点（50355-50360 · 13-p3b-design.md §4.2；
  // 退货登记不足复用 STOCK_NOT_ENOUGH(50251)，不新占码——13 v1.2 备注 1）
  /** 盘点明细为空 / 同单 SKU 重复 / 实物数<0（T3-W2） */
  STATE_STOCKTAKE_ITEMS_INVALID = 50355,
  /** 同商户在途盘点单至多一张（DRAFT/PENDING_APPROVAL 防双重盈亏，T3-W2） */
  STATE_STOCKTAKE_OPEN_EXISTS = 50356,
  /** D-13 批次禁改防御（T4-W1 解除后语义转「专用端点 batch-toggle 外禁改」） */
  STATE_BATCH_FEATURE_NOT_READY = 50360,

  // P3b T4 批次 / 临期 / 清库（50361-50367 · 13-p3b-design.md §4.2；
  // 权威来源：backend ErrorCode.java 实测 v1.4/v1.5 备注）
  /** 批次开关 24h ≤2 次（Redis 计数，T4-W1） */
  STATE_BATCH_TOGGLE_RATE_LIMITED = 50361,
  /** (商户, SKU, 批次号) 唯一冲突（提交预检 + 登记 uk 兜底，T4-W1） */
  STATE_BATCH_NO_DUPLICATE = 50362,
  /** 批次不存在 / 跨商户按不存在（不泄漏存在性，T4-W1） */
  STATE_BATCH_NOT_FOUND = 50363,
  /** 过期批次入库须二次确认（expiredConfirmed=true 重发，T4-W1） */
  STATE_BATCH_EXPIRED_CONFIRM_REQUIRED = 50364,
  /** 非待清理 / 推算剩余 0 / 同批次在途清库单已存在（T4-W2） */
  STATE_CLEARANCE_BATCH_NOT_CLEARABLE = 50365,
  /** 清库实物照片 ≥1 刚性（不受拍照开关影响，T4-W2） */
  STATE_CLEARANCE_PHOTO_REQUIRED = 50366,
  /** 手动一键通知同批次 24h 限 1（D-12，T4-W2） */
  STATE_EXPIRY_NOTIFY_RATE_LIMITED = 50367,

  /** 库存不足（退货登记 D-7 / 出库 / 询价确认 共用；13 §4.2 复用不新占） */
  STATE_STOCK_NOT_ENOUGH = 50251,

  STATE_BILL_NOT_GENERATED = 50301,
  STATE_BILL_DISPATCHED = 50302,
  STATE_BILL_PAID = 50303,
  STATE_BILL_HAS_DISPUTE = 50304,

  // ============ BUSINESS (60xxx) ============
  BUSINESS_INSUFFICIENT_STOCK = 60001,
  BUSINESS_SKU_UNLISTED = 60002,
  BUSINESS_BATCH_EXPIRED = 60003,
  BUSINESS_BATCH_DISABLED = 60004,
  BUSINESS_CAPACITY_FULL = 60005,
  BUSINESS_EXPIRING_BLOCKED = 60006,
  BUSINESS_COUNT_DIFF_TOO_LARGE = 60007,

  BUSINESS_PUBLIC_PRICE_MISSING = 60101,
  BUSINESS_WHOLESALE_PRICE_MISSING = 60102,
  BUSINESS_PRICE_RESOLVE_FAILED = 60103,
  BUSINESS_BATCH_PRICE_LOCKED = 60104,
  BUSINESS_PRICE_BELOW_COST = 60105,
  BUSINESS_CUSTOMER_PRICE_EXISTS = 60106,

  // ============ SYSTEM (9xxxx) ============
  SYSTEM_INTERNAL = 90001,
  SYSTEM_DATABASE = 90002,
  SYSTEM_REDIS = 90003,
  SYSTEM_MQ = 90004,
  SYSTEM_MAINTENANCE = 90005,

  SYSTEM_SMS_UNAVAILABLE = 90101,
  SYSTEM_ASR_UNAVAILABLE = 90102,
  SYSTEM_OSS_UNAVAILABLE = 90103,
  SYSTEM_MAP_UNAVAILABLE = 90104,

  SYSTEM_OPTIMISTIC_LOCK = 90201,
  SYSTEM_LOCK_TIMEOUT = 90202,
  SYSTEM_IDEMPOTENT_DUPLICATE = 90203,
}

/** 错误码大类（按数值段路由） */
export type ErrorCategory =
  | 'AUTH'
  | 'PERMISSION'
  | 'VALIDATION'
  | 'LIMIT'
  | 'STATE'
  | 'BUSINESS'
  | 'SYSTEM'
  | 'OK'
  | 'UNKNOWN'

/**
 * 按 code 数字段路由到大类
 * 这是全局拦截器最关键的函数
 */
export function categoryOf(code: number): ErrorCategory {
  if (code === 0) return 'OK'
  if (code >= 41000 && code <= 41999) return 'AUTH'
  if (code >= 42000 && code <= 42999) return 'PERMISSION'
  if (code >= 43000 && code <= 43999) return 'LIMIT'
  if (code >= 40000 && code <= 40999) return 'VALIDATION'
  if (code >= 50000 && code <= 50999) return 'STATE'
  if (code >= 60000 && code <= 69999) return 'BUSINESS'
  if (code >= 90000 && code <= 99999) return 'SYSTEM'
  return 'UNKNOWN'
}

/**
 * 判断错误码是否会"踢人下线"（需要清 token 跳登录）
 * 仅 41001 / 41002 / 41003 / 41005 / 41004（账号冻结）
 */
export function isLogoutRequired(code: number): boolean {
  return (
    code === ErrorCode.AUTH_TOKEN_MISSING ||
    code === ErrorCode.AUTH_TOKEN_EXPIRED ||
    code === ErrorCode.AUTH_TOKEN_KICKED ||
    code === ErrorCode.AUTH_TOKEN_INVALID ||
    code === ErrorCode.AUTH_USER_FROZEN
  )
}

/**
 * R13 退驻前置不满足段（50310-50329，契约 50312+）
 * 前端命中时应刷新退驻前置自查清单并回显未通过项
 * 注：段内 50319-50322 为 WE 员工管理码，仅出现在员工端点，
 * 本函数只在退驻页面对退驻接口的报错调用，故不会误伤
 */
export function isWithdrawPreconditionFailed(code: number): boolean {
  return code >= 50310 && code <= 50329
}
