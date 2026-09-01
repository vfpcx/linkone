-- V36__p5a_storefront_featured.sql
-- P5-A W4（18-p5-design §2.2）：店铺撮合配置表（归属 tenant 域）。
-- kind=MAIN_SKU（主推商品 ref=skuId）/ PIN_WA（置顶批发商 ref=wholesalerId），sort_order 为展示顺序。
-- 行同存 tenant_id，纳入 TenantLine 白名单；store_id 即店铺归属（tenant 与 store 1:1）。
-- H2(MODE=MySQL) 兼容：标准 SQL 单文件（沿 V35 先例，无方言差异无需 database-specific 拆分）。
-- 索引名加 sf_ 前缀防 H2 全局索引重名（沿 V22 bat_ 先例）。

CREATE TABLE `storefront_featured` (
    `id`         BIGINT      NOT NULL COMMENT '雪花ID',
    `tenant_id`  BIGINT      NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `store_id`   BIGINT      NOT NULL COMMENT '店铺（仓库），tenant 与 store 1:1',
    `kind`       VARCHAR(16) NOT NULL COMMENT 'MAIN_SKU（主推商品）/PIN_WA（置顶批发商）',
    `ref_id`     BIGINT      NOT NULL COMMENT '引用 id：MAIN_SKU=skuId；PIN_WA=wholesalerId',
    `sort_order` INT         NOT NULL DEFAULT 0 COMMENT '展示顺序（0 起，越小越靠前）',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_sf_store_kind_ref` UNIQUE (`store_id`, `kind`, `ref_id`),
    KEY `idx_sf_store_kind_sort` (`store_id`, `kind`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺撮合配置(P5-A W4，18 §2.2；归属 tenant 域)';
