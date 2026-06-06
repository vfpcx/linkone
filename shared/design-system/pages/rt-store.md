# RT 店铺浏览页 · 设计覆写

> 适用：扫店铺码进店、商品详情、提交询价、我的意向单（仅 RT 小程序 + RT H5）
> 覆写自：`../MASTER.md`

## 与 MASTER 的差异

### 1. CTA 主色用「生鲜绿」

```css
--cta-bg:        var(--color-rt-accent);        /* #059669 */
--cta-bg-hover:  var(--color-rt-accent-hover);  /* #047857 */
--cta-text:      #FFFFFF;
```

适用于：
- 「询价」「立即下单」按钮
- 「一键复购」按钮
- 商品卡片右下角的 CTA

**保留藏青主色** 的：顶栏、Tab、表单 submit、危险操作。

### 2. 价格字号加大

商品详情页的主价格：

```css
.product-price {
  font-size: 28px;           /* Display 字号 */
  font-weight: 700;
  color: var(--color-rt-accent);
  font-variant-numeric: tabular-nums;
}
```

起批价 / 起批量副文字：
```css
.product-price-sub {
  font-size: 12px;
  color: var(--color-fg-3);
  margin-left: 8px;
}
```

### 3. "专属优惠"标签

登录后，若 SKU 命中客户专属价：

```
¥110 起批 ¥100     ← 主价（生鲜绿、Display 字号）
[✓ 专属优惠]       ← 绿底白字 Badge，6px 圆角
```

未登录时不展示专属价，只展示公开价（无 Badge）。

### 4. 主推区"热销"标

主推商品卡片右上角：

```
┌─────────┐
│ 🟠 热销 │   ← --color-rt-warm 橙底，10px 字号，斜角
│ [图片]   │
│ ¥120    │
└─────────┘
```

### 5. "店铺距离"小条

店铺页顶部固定显示：
```
XX 海鲜库  📍 距您 8.3 km
营业 06:00-22:00
```
- 📍 图标 + 距离用 `--color-rt-accent` 生鲜绿
- 距离展示遵守 MASTER §15.4 距离规则

### 6. 触屏热区放宽

商品卡片整张可点击（不只 CTA），最小 88×88pt（卡片本身比 MASTER 44×44 更大）。

### 7. 收藏 / 复购 入口

「我的意向单」每条都有 [一键复购] 按钮：

```css
.repurchase-btn {
  background: var(--color-rt-accent);
  color: #fff;
  padding: 8px 16px;
  border-radius: 9999px;   /* Pill */
  font-weight: 500;
}
```

## 不允许的偏离

- ❌ 不要用紫色、粉色、霓虹色（依然遵守 MASTER）
- ❌ 不做"促销倒计时"营销组件（不是电商促销平台）
- ❌ 不做"满减优惠券"组件（v2 评估）
- ❌ 不做横向 banner 轮播（最多 1 张静态主图）
