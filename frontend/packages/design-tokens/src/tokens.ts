/**
 * 仓储云 · Design Tokens · TypeScript 常量
 * 用于 JS/TS 端：图表配色、动态样式、computed 计算
 *
 * 与 tokens.css 一一对应。修改其一时另一并改。
 */

// ============ 颜色 ============
export const colors = {
  brand: {
    primary: '#0F172A',
    primaryOn: '#FFFFFF',
    accent: '#0369A1',
    accentHover: '#0284C7',
    accentOn: '#FFFFFF',
  },
  status: {
    success: '#059669',
    successBg: '#D1FAE5',
    warning: '#D97706',
    warningBg: '#FEF3C7',
    danger: '#DC2626',
    dangerBg: '#FEE2E2',
    info: '#0369A1',
    infoBg: '#DBEAFE',
  },
  fg: {
    1: '#020617',
    2: '#334155',
    3: '#64748B',
    4: '#94A3B8',
  },
  bg: {
    1: '#FFFFFF',
    2: '#F8FAFC',
    3: '#F1F5F9',
    disabled: '#F1F5F9',
  },
  border: {
    1: '#E2E8F0',
    2: '#CBD5E1',
    focus: '#0369A1',
  },
  rt: {
    accent: '#059669',
    accentHover: '#047857',
    warm: '#D97706',
  },
} as const

// ============ ECharts 默认配色顺序 ============
export const chartPalette = [
  '#0369A1', // 操作蓝     - 主指标
  '#059669', // 仓储绿     - 次指标
  '#D97706', // 橙黄       - 第三类
  '#DC2626', // 红         - 异常
  '#6366F1', // 靛蓝       - 第四类
] as const

// ============ 字体 ============
export const fontFamily = {
  sans: `'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, 'Hiragino Sans GB', Arial, sans-serif`,
  mono: `'JetBrains Mono', 'Fira Code', 'SF Mono', Consolas, 'Courier New', monospace`,
} as const

export const fontSize = {
  display: '28px',
  h1: '22px',
  h2: '18px',
  h3: '16px',
  body: '14px',
  bodyLg: '16px',
  caption: '12px',
} as const

export const fontWeight = {
  regular: 400,
  medium: 500,
  semibold: 600,
  bold: 700,
} as const

// ============ 间距 ============
export const space = {
  0: '0px',
  1: '4px',
  2: '8px',
  3: '12px',
  4: '16px',
  5: '20px',
  6: '24px',
  8: '32px',
  10: '40px',
  12: '48px',
  16: '64px',
} as const

// ============ 圆角 ============
export const radius = {
  sm: '4px',
  base: '6px',
  md: '8px',
  lg: '12px',
  full: '9999px',
} as const

// ============ 阴影 ============
export const shadow = {
  sm: '0 1px 2px 0 rgba(15, 23, 42, 0.04)',
  base: '0 1px 3px 0 rgba(15, 23, 42, 0.08), 0 1px 2px -1px rgba(15, 23, 42, 0.04)',
  md: '0 4px 6px -1px rgba(15, 23, 42, 0.08), 0 2px 4px -2px rgba(15, 23, 42, 0.04)',
  lg: '0 10px 15px -3px rgba(15, 23, 42, 0.10), 0 4px 6px -4px rgba(15, 23, 42, 0.05)',
} as const

// ============ 动画 ============
export const duration = {
  fast: 150,
  base: 200,
  slow: 300,
} as const

export const easing = {
  standard: 'cubic-bezier(0.2, 0, 0, 1)',
  decelerate: 'cubic-bezier(0, 0, 0, 1)',
  accelerate: 'cubic-bezier(0.3, 0, 1, 1)',
} as const

// ============ Z-index ============
export const zIndex = {
  base: 0,
  dropdown: 10,
  sticky: 20,
  fixed: 30,
  overlay: 40,
  drawer: 50,
  modal: 100,
  popover: 200,
  toast: 1000,
} as const

// ============ 工具：按利用率取容量进度条配色 ============
export function getCapacityColor(utilization: number): string {
  if (utilization < 70) return colors.status.success
  if (utilization < 90) return colors.status.warning
  return colors.status.danger
}

// ============ 响应式断点 ============
export const breakpoint = {
  sm: 375,
  md: 768,
  lg: 1024,
  xl: 1440,
} as const
