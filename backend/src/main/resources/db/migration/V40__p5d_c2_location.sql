-- V40__p5d_c2_location.sql
-- C2 货位功能（US-WK-05，architecture/25-p5-c-c2 §3）：仓级开关 + 出入库登记货位 + 批次移库。
-- 口径（D-C-1~1d）：货位=自由文本 ≤64 不做字典；开关 locationEnabled 默认关（关=出入库零货位字段/零校验）；
-- 挂载=单据留痕列 + batches.location + batch_location_logs 变更记录；不建 per-location 库存（计费/对账/FIFO 铁律零触碰）。
-- 单文件标准 SQL（沿 V36/V39 先例，H2 MODE=MySQL 兼容）；索引名加 bll_ 前缀防 H2 全局索引重名（沿 V22 bat_ 先例）。
-- 注：需求档案 17 §2.3 草案「V39/V39b」为早期假设，V39 已被 C3 占用 → 本波实际 V40（K-8）。

-- 1) 仓级货位开关（默认关；TA 经通用店铺设置 PUT /tenant/me 开关，无 batch-toggle 式副作用）
ALTER TABLE `tenant_settings` ADD COLUMN `location_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '货位功能开关（C2，默认 0=关闭；=1 出入库登记货位必填+批次可移库）';

-- 2) 批次货位列（登记簿行；入库登记带货位且有批次号时写入；移库端点更新）
ALTER TABLE `batches` ADD COLUMN `location` VARCHAR(64) NULL COMMENT '货位号（自由文本 ≤64，C2；空=未指定）';

-- 3) 入库单货位留痕（locationEnabled=1 时登记必填 50822）
ALTER TABLE `inbound_requests` ADD COLUMN `location` VARCHAR(64) NULL COMMENT '入库登记货位（C2；单据留痕，批次行同步）';

-- 4) 出库单拣出货位留痕（locationEnabled=1 时登记出库/代建必填 50822；只记录拣货指示，不动批次/流水——方案 C 铁律）
ALTER TABLE `outbound_requests` ADD COLUMN `location` VARCHAR(64) NULL COMMENT '拣出货位（C2；出库登记/代建登记留痕，零记账副作用）';

-- 5) 批次移库变更记录（US-WK-05 验收：from/to/操作人/时间；仅差异落行）
CREATE TABLE `batch_location_logs` (
    `id`                BIGINT       NOT NULL COMMENT '雪花ID',
    `tenant_id`         BIGINT       NOT NULL COMMENT '归属租户（TenantLine 兜底白名单）',
    `wholesaler_id`     BIGINT       NOT NULL COMMENT '归属商户（冗余展示）',
    `sku_id`            BIGINT       NOT NULL COMMENT '商品 SKU（冗余展示）',
    `batch_id`          BIGINT       NOT NULL COMMENT '批次 id',
    `from_location`     VARCHAR(64)  NULL     COMMENT '原货位（无旧值为 NULL）',
    `to_location`       VARCHAR(64)  NULL     COMMENT '新货位（清空时为 NULL）',
    `operator_user_id`  BIGINT       NOT NULL COMMENT '操作人（本租户 WK/TA）',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_bll_batch` (`batch_id`),
    KEY `idx_bll_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批次移库变更记录(C2 US-WK-05，25-p5-c-c2 §3.2；归属 inventory 域)';
