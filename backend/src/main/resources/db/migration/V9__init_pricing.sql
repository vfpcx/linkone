-- V9: P2 定价能力 Wave 1（两表一起建：专属价 + 价格变更日志）

-- 客户专属价（wholesaler + rt_phone + sku 唯一）
CREATE TABLE `customer_prices` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `wholesaler_id` BIGINT NOT NULL COMMENT '批发商ID',
    `sku_id` BIGINT NOT NULL COMMENT 'SKU ID',
    `rt_phone` VARCHAR(32) NOT NULL COMMENT '客户身份=RT手机号(同 inquiry_requests.rt_phone)',
    `unit_price` DECIMAL(12,2) NOT NULL COMMENT '专属单价(>0)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态:ACTIVE/DISABLED/EXPIRED',
    `source` VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT '来源:manual/from_inquiry',
    `source_doc_no` VARCHAR(64) NULL COMMENT '来源单据号(from_inquiry 时填)',
    `expire_at` DATETIME NULL COMMENT '失效时间(空=永久)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` BIGINT NULL COMMENT '创建人用户ID',
    `deleted_at` DATETIME NULL COMMENT '软删时间(空=未删)',
    UNIQUE KEY `uk_custprice_wh_phone_sku` (`wholesaler_id`, `rt_phone`, `sku_id`),
    INDEX `idx_custprice_sku` (`sku_id`),
    INDEX `idx_custprice_phone` (`rt_phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户专属价(P2 定价 Wave 1)';

-- 价格变更日志（Wave 2/3 批量调价沉淀，Wave 1 仅建表不写）
CREATE TABLE `price_change_logs` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `wholesaler_id` BIGINT NOT NULL COMMENT '批发商ID',
    `batch_no` VARCHAR(64) NOT NULL COMMENT '批次号',
    `change_type` VARCHAR(24) NOT NULL COMMENT '变更类型:PUBLIC_PRICE/CUSTOMER_PRICE',
    `adjust_mode` VARCHAR(24) NOT NULL COMMENT '调整方式:PCT_UP/PCT_DOWN/SET_VALUE/DELTA/DISABLE/SET_EXPIRE',
    `affected_count` INT NOT NULL DEFAULT 0 COMMENT '影响条数',
    `target_summary` VARCHAR(512) NULL COMMENT '目标范围摘要',
    `before_after_json` TEXT NULL COMMENT '变更前后快照JSON',
    `operator_user_id` BIGINT NULL COMMENT '操作人用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_pricelog_wholesaler` (`wholesaler_id`),
    INDEX `idx_pricelog_batch` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='价格变更日志(P2 定价 Wave 2/3)';
