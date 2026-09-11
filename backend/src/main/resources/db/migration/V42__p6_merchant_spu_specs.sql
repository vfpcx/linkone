-- V42: 商户自建聚合 SPU + 结构化规格模板（P6 商品规格模型；混合 SPU 决策 2026-09-09）
--
-- 目标模型（与现有 skus 平级演进，库存/单据/交易链仍按 skus.id 流转，零改动）：
--   * spus 升级为「双归属聚合层」：
--       owner_type='PLATFORM'（OPS 平台标品，既有语义不变，默认值）
--       owner_type='TENANT'  （租户/商户自建聚合 SPU，带 tenant_id + wholesaler_id）
--   * 同一 SPU 下多 SKU 通过 skus.spu_id 关联（V5 已留列）；SPU 携带规格模板
--     spec_schema（JSON：[{"name":"包装","options":["5L/桶","10L/桶"]}]），
--     笛卡尔积批量生成 SKU，每个组合 = 独立 SKU（价格/库存各自独立）。
--   * skus.spec_key：结构化规格键（维度=取值 的有序紧凑 JSON），供同 SPU 下组合唯一性
--     校验与摘要推导；历史自由文本 spec 列保留（兼容既有单规格 SKU 与单据）。
--
-- 隔离说明：spus 仍为平台级表（不进 TenantLine 白名单，V38 先例）——PLATFORM 行天然平台级；
-- TENANT 行由 service 层按 (owner_type='TENANT' AND tenant_id=?) 显式过滤，防跨租户读取。
-- H2(MODE=MySQL) 兼容：沿用 V38/V40 逐条 ADD COLUMN 写法 + V15/V18 先例的独立 CREATE INDEX；
-- 唯一键用 V37 已验证的 `ALTER TABLE ... ADD UNIQUE KEY`（H2 MySQL 兼容模式接受）。

ALTER TABLE `spus` ADD COLUMN `owner_type` VARCHAR(16) NOT NULL DEFAULT 'PLATFORM'
    COMMENT '归属类型：PLATFORM=OPS平台标品 / TENANT=租户商户自建聚合SPU';
ALTER TABLE `spus` ADD COLUMN `tenant_id` BIGINT NULL
    COMMENT '所属租户（owner_type=TENANT 时必填；PLATFORM 恒 NULL）';
ALTER TABLE `spus` ADD COLUMN `wholesaler_id` BIGINT NULL
    COMMENT '所属商户（owner_type=TENANT 时必填；PLATFORM 恒 NULL）';
ALTER TABLE `spus` ADD COLUMN `spec_schema` VARCHAR(2048) NULL
    COMMENT '规格模板 JSON：[{"name":"包装","options":["5L/桶","10L/桶"]},...]；TENANT 行必填';

CREATE INDEX `idx_spu_owner_tenant` ON `spus` (`owner_type`, `tenant_id`);
CREATE INDEX `idx_spu_wholesaler` ON `spus` (`wholesaler_id`);

ALTER TABLE `skus` ADD COLUMN `spec_key` VARCHAR(512) NULL
    COMMENT '结构化规格键（规格模板维度=取值的紧凑 JSON，用于同SPU组合唯一与摘要推导；历史自由 spec 文本 SKU 为 NULL）';
-- 组合唯一性 DB 兜底：同 SPU 下 spec_key 唯一（历史/平台挂接行 spec_key 恒 NULL，NULL 互不冲突）
ALTER TABLE `skus` ADD UNIQUE KEY `uk_sku_spu_spec` (`spu_id`, `spec_key`);
