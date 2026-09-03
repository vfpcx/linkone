/**
 * 全局配置（uni-app 移动端）。
 *
 * API_BASE：
 *  - H5 开发：vite dev proxy 把 /api → http://localhost:8080（见 vite.config.ts），保持相对路径即可；
 *  - 微信小程序：没有 dev proxy，需在「request 合法域名」中配置后端并开启不校验调试；
 *    打包上线前把本常量改为完整域名，例如 https://api.xxx.com/api/v1（勿带末尾斜杠）。
 *  - 业务代码一律以 /rt/... 相对路径发起请求，不在业务层拼 host。
 */
export const API_BASE = '/api/v1'

/** 手机号格式（中国大陆 11 位） */
export const PHONE_RE = /^1\d{10}$/

/** 手机号输入框统一 maxlength */
export const PHONE_MAX_LEN = 11
