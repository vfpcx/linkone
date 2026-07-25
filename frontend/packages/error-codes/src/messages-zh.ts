/**
 * 错误码中文用户提示 · 与 05-error-codes.md userMessage 对齐
 *
 * 用法：
 *   const msg = messagesZh[code] ?? backendMsg ?? '操作失败'
 */

import { ErrorCode } from './codes'

export const messagesZh: Record<number, string> = {
  [ErrorCode.OK]: '成功',

  // AUTH
  [ErrorCode.AUTH_TOKEN_MISSING]: '您尚未登录，请先登录',
  [ErrorCode.AUTH_TOKEN_EXPIRED]: '登录已过期，请重新登录',
  [ErrorCode.AUTH_TOKEN_KICKED]: '您的账号已在其他设备登录',
  [ErrorCode.AUTH_USER_FROZEN]: '账号已被冻结，请联系平台',
  [ErrorCode.AUTH_TOKEN_INVALID]: '登录凭证无效，请重新登录',

  [ErrorCode.AUTH_INVALID_CREDENTIALS]: '账号或密码错误',
  [ErrorCode.AUTH_ACCOUNT_LOCKED]: '账号已锁定，请稍后重试',
  [ErrorCode.AUTH_PHONE_NOT_REGISTERED]: '手机号未注册',
  [ErrorCode.AUTH_PHONE_ALREADY_REGISTERED]: '该手机号已注册，请直接登录',
  [ErrorCode.AUTH_NEW_PASSWORD_SAME]: '新密码与旧密码相同',
  [ErrorCode.AUTH_NEW_PASSWORD_HISTORY]: '新密码不能与最近 5 次密码相同',
  [ErrorCode.AUTH_OLD_PASSWORD_WRONG]: '旧密码错误',
  [ErrorCode.AUTH_ALL_ROLES_DISABLED]: '账号已被禁用，请联系商户管理员',

  [ErrorCode.AUTH_SMS_EXPIRED]: '验证码已过期，请重新获取',
  [ErrorCode.AUTH_SMS_WRONG]: '验证码错误',
  [ErrorCode.AUTH_SMS_LOCKOUT]: '验证码错误次数过多，请 15 分钟后重试',
  [ErrorCode.AUTH_SMS_INTERVAL]: '请 60 秒后再获取验证码',
  [ErrorCode.AUTH_SMS_DAILY_LIMIT]: '今日验证码次数已达上限',
  [ErrorCode.AUTH_SMS_NOT_FOUND]: '验证码尚未获取，请先获取',

  [ErrorCode.AUTH_INVITE_INVALID]: '邀请码无效',
  [ErrorCode.AUTH_INVITE_EXPIRED]: '邀请码已过期',
  [ErrorCode.AUTH_INVITE_EXHAUSTED]: '邀请码已用完',
  [ErrorCode.AUTH_INVITE_ROLE_MISMATCH]: '邀请码与目标角色不匹配',

  // PERMISSION
  [ErrorCode.PERMISSION_ROLE_DENIED]: '您没有此操作的权限',
  [ErrorCode.PERMISSION_OPS_ONLY]: '平台操作仅限平台运维角色',
  [ErrorCode.PERMISSION_TA_ONLY]: '仅限租户管理员操作',
  [ErrorCode.PERMISSION_WE_LIMITED]: '批发商员工无此权限，请联系批发商管理员',
  [ErrorCode.PERMISSION_CROSS_STORE]: '仅限本店库管员操作',
  [ErrorCode.PERMISSION_CROSS_TENANT]: '您没有访问此租户数据的权限',
  [ErrorCode.PERMISSION_TENANT_LEAK]: '系统正在处理，请稍后重试',
  [ErrorCode.PERMISSION_WA_NOT_IN_TENANT]: '您的批发商身份不在此租户下',
  [ErrorCode.PERMISSION_NOT_OWNER]: '此单据非您所有，无法操作',
  [ErrorCode.PERMISSION_SKU_OWNERSHIP]: '此 SKU 非您所属批发商，无法修改',
  [ErrorCode.PERMISSION_INQUIRY_RECIPIENT]: '您不是此询价单的接收方',

  // LIMIT
  [ErrorCode.LIMIT_RATE]: '操作过于频繁，请稍后再试',
  [ErrorCode.LIMIT_QUOTA]: '今日操作次数已达上限',
  [ErrorCode.LIMIT_SMS]: '短信发送频率受限，请稍后',
  [ErrorCode.LIMIT_ASR]: '语音识别配额已用完',

  // VALIDATION
  [ErrorCode.VALIDATION_FAILED]: '参数校验失败',
  [ErrorCode.VALIDATION_JSON]: '请求体格式错误',
  [ErrorCode.VALIDATION_REQUIRED]: '缺少必填参数',
  [ErrorCode.VALIDATION_RANGE]: '参数超出范围',
  [ErrorCode.VALIDATION_PHONE_FORMAT]: '手机号格式不正确',
  [ErrorCode.VALIDATION_PASSWORD_WEAK]: '密码强度不足（6-20 位，含字母数字）',
  [ErrorCode.VALIDATION_AMOUNT_FORMAT]: '金额格式不正确',
  [ErrorCode.VALIDATION_DATE_FORMAT]: '日期格式不正确',
  [ErrorCode.VALIDATION_COORD_RANGE]: '坐标超出有效范围',
  [ErrorCode.VALIDATION_FILE_SIZE]: '文件大小超过限制',
  [ErrorCode.VALIDATION_FILE_TYPE]: '文件类型不支持',
  [ErrorCode.VALIDATION_QTY_POSITIVE]: '数量必须大于 0',
  [ErrorCode.VALIDATION_QTY_BELOW_THRESHOLD]: '数量低于起批量',
  [ErrorCode.VALIDATION_PRICE_POSITIVE]: '单价必须大于 0',
  [ErrorCode.VALIDATION_DISCOUNT_EXCEEDS]: '折扣金额不能大于小计',
  [ErrorCode.VALIDATION_PROD_DATE_FUTURE]: '生产日期不能晚于今天',
  [ErrorCode.VALIDATION_EXPIRY_BEFORE_PROD]: '保质期日期不能早于生产日期',
  [ErrorCode.VALIDATION_DUPLICATE_CUSTOMER_PRICE]:
    '同一手机号同一 SKU 只能设置一个有效专属价',

  // STATE
  [ErrorCode.STATE_DOC_CHANGED]: '单据状态已变更，请刷新后重试',
  [ErrorCode.STATE_DOC_INVALID_TRANSITION]: '当前状态不允许此操作',
  [ErrorCode.STATE_INBOUND_REGISTERED]: '入库单已登记，不能撤回',
  [ErrorCode.STATE_PROXY_OUTBOUND_NO_DISPUTE]: '代建出库单不可异议',
  [ErrorCode.STATE_INQUIRY_CONFIRMED]: '询价已确认，不能再撤回',
  [ErrorCode.STATE_INQUIRY_EXPIRED]: '询价已过期，请重新提交',
  [ErrorCode.STATE_AUTO_CONFIRMED_72H]: '72 小时确认期已过，单据自动确认',
  [ErrorCode.STATE_UNDER_ARBITRATION]: '此单据正在仲裁中，请等待平台处理',
  [ErrorCode.STATE_TENANT_PENDING]: '租户审核中，暂不可操作',
  [ErrorCode.STATE_TENANT_FROZEN]: '租户已冻结，所有操作受限',
  [ErrorCode.STATE_TENANT_OFFLINE]: '租户已下线',
  [ErrorCode.STATE_WA_PENDING]: '批发商入驻审核中',
  [ErrorCode.STATE_WA_WITHDRAWN]: '批发商已退驻',
  [ErrorCode.STATE_WA_APPLICATION_NOT_AUDITABLE]: '申请不存在或状态已变化，请刷新后重试',
  [ErrorCode.STATE_WA_ALREADY_ONBOARDED]: '该账号已入驻仓库，无需重复申请',
  // DEF-2（Wave6 §30）：对外一律中性文案，不透出「黑名单」字样（与后端 message 同步）
  [ErrorCode.STATE_WA_BLACKLISTED]: '暂不满足入驻条件，请联系平台客服',
  [ErrorCode.STATE_INVITE_REVOKED]: '员工注册码已作废，请向管理员索取新码',
  [ErrorCode.STATE_BLACKLIST_DUPLICATE]: '该键值已在黑名单中',
  [ErrorCode.STATE_BLACKLIST_NOT_FOUND]: '黑名单条目不存在或已解除',
  [ErrorCode.STATE_WITHDRAW_STOCK_NOT_CLEARED]: '仍有在库库存，请清空后再申请退驻',
  [ErrorCode.STATE_WA_NOT_ACTIVE]: '商户已非营业状态，无法发起新业务',
  [ErrorCode.STATE_WITHDRAW_OPEN_DOCS]: '存在未结单据，请处理完成后再申请退驻',
  [ErrorCode.STATE_WITHDRAW_NOT_AUDITABLE]: '退驻申请状态已变化，请刷新后重试',
  [ErrorCode.STATE_WITHDRAW_DUPLICATE]: '已有退驻申请在审批中，请勿重复提交',
  [ErrorCode.STATE_RESTORE_WINDOW_EXPIRED]: '60 天恢复期已过，请重新申请入驻',
  [ErrorCode.STATE_WHOLESALER_TRANSITION_INVALID]: '商户当前状态不允许此操作',
  [ErrorCode.STATE_EMPLOYEE_PERMISSION_INVALID]: '授权项不合法，仅支持「改价 / 询价确认」',
  [ErrorCode.STATE_EMPLOYEE_NOT_FOUND]: '员工不存在或不属于本商户',
  [ErrorCode.STATE_EMPLOYEE_STATUS_INVALID]: '员工当前状态不允许此操作，请刷新后重试',
  [ErrorCode.STATE_EMPLOYEE_RESTORE_EXPIRED]: '已超过 30 天恢复期，员工已永久移除',
  [ErrorCode.STATE_BILL_NOT_GENERATED]: '账单尚未生成',
  [ErrorCode.STATE_BILL_DISPATCHED]: '账单已下发，不能直接调整',
  [ErrorCode.STATE_BILL_PAID]: '账单已结清，无法继续操作',
  [ErrorCode.STATE_BILL_HAS_DISPUTE]: '该账单存在未处理申诉',

  // BUSINESS
  [ErrorCode.BUSINESS_INSUFFICIENT_STOCK]: '库存不足',
  [ErrorCode.BUSINESS_SKU_UNLISTED]: 'SKU 已下架',
  [ErrorCode.BUSINESS_BATCH_EXPIRED]: '该批次已过期',
  [ErrorCode.BUSINESS_BATCH_DISABLED]: '批次开关关闭，无法按批次操作',
  [ErrorCode.BUSINESS_CAPACITY_FULL]: '仓库容量已满，无法入库',
  [ErrorCode.BUSINESS_EXPIRING_BLOCKED]: '临期阈值内不可此操作',
  [ErrorCode.BUSINESS_COUNT_DIFF_TOO_LARGE]: '盘点差异过大需租户管理员二次确认',
  [ErrorCode.BUSINESS_PUBLIC_PRICE_MISSING]: 'SKU 公开价未设置，无法浏览',
  [ErrorCode.BUSINESS_WHOLESALE_PRICE_MISSING]: '起批价缺失，请补全',
  [ErrorCode.BUSINESS_PRICE_RESOLVE_FAILED]: '价格匹配失败，请联系批发商管理员',
  [ErrorCode.BUSINESS_BATCH_PRICE_LOCKED]: '批量调价正在执行中，请稍后',
  [ErrorCode.BUSINESS_PRICE_BELOW_COST]: '议价价格低于成本价',
  [ErrorCode.BUSINESS_CUSTOMER_PRICE_EXISTS]: '客户专属价已存在并生效',

  // SYSTEM
  [ErrorCode.SYSTEM_INTERNAL]: '系统繁忙，请稍后再试',
  [ErrorCode.SYSTEM_DATABASE]: '数据库异常',
  [ErrorCode.SYSTEM_REDIS]: '缓存异常',
  [ErrorCode.SYSTEM_MQ]: '消息队列异常',
  [ErrorCode.SYSTEM_MAINTENANCE]: '系统维护中，请稍候',
  [ErrorCode.SYSTEM_SMS_UNAVAILABLE]: '短信服务暂不可用',
  [ErrorCode.SYSTEM_ASR_UNAVAILABLE]: '语音服务暂不可用',
  [ErrorCode.SYSTEM_OSS_UNAVAILABLE]: '文件存储暂不可用',
  [ErrorCode.SYSTEM_MAP_UNAVAILABLE]: '地图服务暂不可用',
  [ErrorCode.SYSTEM_OPTIMISTIC_LOCK]: '操作冲突，请刷新后重试',
  [ErrorCode.SYSTEM_LOCK_TIMEOUT]: '资源锁定中，请稍后',
  [ErrorCode.SYSTEM_IDEMPOTENT_DUPLICATE]: '重复请求已被拦截',
}

/** 取错误码的中文提示，找不到时退化为 backendMessage */
export function getMessage(code: number, fallback?: string): string {
  return messagesZh[code] ?? fallback ?? '操作失败，请稍后再试'
}
