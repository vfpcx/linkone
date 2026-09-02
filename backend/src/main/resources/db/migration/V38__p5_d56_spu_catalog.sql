-- V38__p5_d56_spu_catalog.sql
-- P5-D D56（22-p5-d56-catalog-design §2.2）：平台标品库 spus 表落地 + skus 标品快照列。
-- 平台级表（无 tenant_id 列）：不在 TenantLine 白名单，天然不做租户隔离，OPS 管辖（announcements 先例 V35）。
-- H2(MODE=MySQL) 兼容：标准 SQL 单文件（沿 V35/V36 先例，无方言差异无需 database-specific 拆分）。

CREATE TABLE `spus` (
    `id`                 BIGINT       NOT NULL COMMENT '雪花ID',
    `spu_code`           VARCHAR(32)  NOT NULL COMMENT '平台编码（OPS 可填；空则自动 GSPU-xxx，全局唯一）',
    `name`               VARCHAR(128) NOT NULL COMMENT '标品名称',
    `category_l1`        VARCHAR(64)  NOT NULL COMMENT '一级品类（预置字典中文文本）',
    `category_l2`        VARCHAR(64)  NOT NULL COMMENT '二级品类',
    `brand`              VARCHAR(64)  NULL COMMENT '品牌（自由文本）',
    `standard_image_url` VARCHAR(512) NULL COMMENT '标准图',
    `note`               VARCHAR(256) NULL COMMENT '备注',
    `status`             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/OFFLINE/MERGED（状态机，合并源/下架源不可再操作）',
    `merged_to_spu_id`   BIGINT       NULL COMMENT '合并源指向的新主标品（仅 MERGED 非空）',
    `created_by`         BIGINT       NULL COMMENT '创建人（OPS）',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_spu_code` (`spu_code`),
    KEY `idx_spu_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台标品 SPU(P5-D D56，22 §2；平台级表，OPS 管辖)';

-- skus 标品快照列（D-B-6）：挂接/合并时整体刷新，列表免 join；历史自由 spec 文本保留不动。
-- 注：H2(MODE=MySQL) 不支持 AFTER 列位子句与单条多列 ADD，逐条 ADD（列追加表尾，SELECT 显式列名不受影响）。
ALTER TABLE `skus` ADD COLUMN `spu_name` VARCHAR(128) NULL;
ALTER TABLE `skus` ADD COLUMN `spu_category_l1` VARCHAR(64) NULL;
ALTER TABLE `skus` ADD COLUMN `spu_category_l2` VARCHAR(64) NULL;
