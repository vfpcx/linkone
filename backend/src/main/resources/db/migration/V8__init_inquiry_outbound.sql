-- V8: phase-1 C2 询价+出库表（两表一起建）

-- 询价单主表
CREATE TABLE `inquiry_requests` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `doc_no` VARCHAR(64) NOT NULL UNIQUE COMMENT '询价单号(INQUIRY)',
    `store_id` BIGINT NOT NULL COMMENT '店铺ID(进店来源)',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `wholesaler_id` BIGINT NOT NULL COMMENT '批发商ID',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态:PENDING/CONFIRMED/COMPLETED',
    `rt_phone` VARCHAR(32) NOT NULL COMMENT 'RT手机号',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `confirmed_at` DATETIME NULL COMMENT '确认时间',
    INDEX `idx_inq_req_tenant_id` (`tenant_id`),
    INDEX `idx_inq_req_wholesaler_id` (`wholesaler_id`),
    INDEX `idx_inq_req_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='询价单(phase-1 C2)';

-- 询价单明细
CREATE TABLE `inquiry_items` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `inquiry_id` BIGINT NOT NULL COMMENT '询价单ID',
    `sku_id` BIGINT NOT NULL COMMENT 'SKU ID',
    `qty` INT NOT NULL COMMENT '询价数量',
    `unit_price_snapshot` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '单价快照',
    `moq_price_snapshot` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '起批价快照',
    `moq_qty_snapshot` INT NOT NULL DEFAULT 0 COMMENT '起批量快照',
    `deal_price` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '成交价(phase-1=单价快照)',
    INDEX `idx_inq_item_inquiry_id` (`inquiry_id`),
    INDEX `idx_inq_item_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='询价单明细';

-- 出库单
CREATE TABLE `outbound_requests` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `doc_no` VARCHAR(64) NOT NULL UNIQUE COMMENT '出库单号(OUTBOUND)',
    `inquiry_id` BIGINT NULL COMMENT '关联询价单ID(可空,phase-1必有)',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `wholesaler_id` BIGINT NOT NULL COMMENT '批发商ID',
    `sku_id` BIGINT NOT NULL COMMENT 'SKU ID',
    `qty` INT NOT NULL COMMENT '出库数量',
    `status` VARCHAR(32) NOT NULL DEFAULT 'COMPLETED' COMMENT '状态(phase-1默认COMPLETED)',
    `wk_user_id` BIGINT NULL COMMENT '登记人(WK)用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_outb_req_tenant_id` (`tenant_id`),
    INDEX `idx_outb_req_wholesaler_id` (`wholesaler_id`),
    INDEX `idx_outb_req_inquiry_id` (`inquiry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库单(phase-1 C2自动生成)';
