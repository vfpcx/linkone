<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

onLaunch(() => {
  // 预留：登录态恢复 / 全局初始化
  console.log('[仓储云移动端] 启动')
})
</script>

<style lang="scss">
/* 全局样式（与 design-tokens 对齐的浅色底） */
page {
  background-color: #f8fafc;
  color: #020617;
  font-size: 28rpx;
  -webkit-font-smoothing: antialiased;
}

/* 全局按钮（uni button 默认边框/内边距重置；页面类叠加尺寸） */
button {
  padding: 0;
  margin: 0;
  background: transparent;
}

button::after {
  border: none;
}

.btn-primary {
  background: $cc-accent;
  color: $cc-accent-on;
}

.btn-ghost {
  background: $cc-bg-3;
  color: $cc-fg-2;
}

.btn-primary,
.btn-ghost {
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12rpx;
  font-size: 30rpx;
  line-height: 1.2;
}

.btn-primary[disabled],
.btn-ghost[disabled] {
  opacity: 0.55;
}
</style>
