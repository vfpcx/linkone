-- V22__p3b_batches.sql
-- P3b T4-W1（13-p3b-design §3.1/§4.1，D-11=C）：批次登记簿 + 入库批次列 + 流水批次标识。
-- 方案 C 铁律：交易路径零改动——batches 是登记簿（记录每批入库量与保质期），
-- remaining_qty 为 02:00 离线 FIFO 推算值（非记账值）；出库流水不落 batch_id。
-- 1) batches：一批一行（uk 商户+SKU+批次号，冲突 50362）；默认批次 DEFAULT-{YYYYMMDD} 吸收启用时刻存量。
-- 2) inbound_requests：批次三字段（批次开关启用时提交/登记必填；过期二次确认 50364）。
-- 3) stock_movements.batch_id：仅 INBOUND / EXPIRY_CLEARANCE / CORRECTION_IN/OUT 落值（方案 C 定义）。
-- 4) tenant_settings.batch_enabled_at：FIFO 推算的流水切割时点（启用前历史被默认批次快照吸收）。
-- H2(MODE=MySQL) 兼容写法沿 V11/V19/V20 先例；索引前缀 bat_/mv_ 防 H2 索引名全局冲突。

-- 1) 批次登记簿（13 §3.1）
CREATE TABLE `batches` (
    `id`                   BIGINT       NOT NULL COMMENT '雪花ID',
    `tenant_id`            BIGINT       NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`        BIGINT       NOT NULL COMMENT '归属商户',
    `sku_id`               BIGINT       NOT NULL COMMENT '商品 SKU',
    `batch_no`             VARCHAR(64)  NOT NULL COMMENT '批次号（默认批次 DEFAULT-{YYYYMMDD}；同日重启用追加序号后缀）',
    `production_date`      DATE         NULL     COMMENT '生产日期（默认批次可补录）',
    `expiry_date`          DATE         NULL     COMMENT '到效期（NULL=不参与临期/归零扫描；默认批次可补录）',
    `initial_qty`          INT          NOT NULL COMMENT '批次累计入库件数（INBOUND 落 batch_id 时累加；默认批次=启用时刻池 qty 快照）',
    `remaining_qty`        INT          NOT NULL COMMENT 'FIFO 推算剩余（02:00 Job 覆写，非记账值，UI 标注「推算」）',
    `status`               VARCHAR(16)  NOT NULL COMMENT 'IN_STOCK/SOLD_OUT/EXPIRING/PENDING_CLEARANCE/CLEARED/CLOSED',
    `source`               VARCHAR(16)  NOT NULL COMMENT 'INBOUND（入库登记）/DEFAULT（开关启用生成）',
    `expiring_notified_at` DATETIME     NULL     COMMENT 'D-12 去重锚点：进入 EXPIRING 首次通知落值，状态不变不重发',
    `manual_notified_at`   DATETIME     NULL     COMMENT 'WK 一键通知 24h 限 1 比对锚点（50367，T4-W2）',
    `cleared_at`           DATETIME     NULL     COMMENT '清库时刻（QK 审批通过，T4-W2）',
    `created_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_bat_ws_sku_no` UNIQUE (`wholesaler_id`, `sku_id`, `batch_no`),
    KEY `idx_bat_ws_sku` (`wholesaler_id`, `sku_id`),
    KEY `idx_bat_tenant_status_expiry` (`tenant_id`, `status`, `expiry_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批次登记簿(P3b T4-W1，D-11=C：FIFO 离线推算)';

-- 2) 入库单批次三字段（批次启用时提交/登记必填：40205/40206；过期二次确认 50364）
ALTER TABLE `inbound_requests` ADD COLUMN `batch_no` VARCHAR(64) NULL COMMENT '批次号（批次开关启用时必填；登记时写入登记簿）';
ALTER TABLE `inbound_requests` ADD COLUMN `production_date` DATE NULL COMMENT '生产日期（≤今天，40205）';
ALTER TABLE `inbound_requests` ADD COLUMN `expiry_date` DATE NULL COMMENT '到效期（>生产日期，40206；≤今天登记需二次确认 50364）';

-- 3) 流水批次标识（仅 INBOUND/EXPIRY_CLEARANCE/CORRECTION_IN/OUT 落值，出库流水不落——方案 C 定义）
ALTER TABLE `stock_movements` ADD COLUMN `batch_id` BIGINT NULL COMMENT '批次标识（方案 C：仅入库/清库/纠错流水落值，出库不落）';
CREATE INDEX `idx_mv_batch` ON `stock_movements` (`batch_id`);

-- 4) FIFO 推算切割时点（启用前历史流水已被默认批次 initial_qty 快照吸收，不重复扣抵）
ALTER TABLE `tenant_settings` ADD COLUMN `batch_enabled_at` DATETIME NULL COMMENT '批次功能最近启用时刻（推算的流水切割时点，13 §3.2）';
