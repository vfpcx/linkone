-- V33 反向 rename 脚本（PII-W8 明文收缩 · 窗口 1 秒级回滚）
-- ============================================================
-- 适用窗口：仅当 V33（rename 明文列 → *__bak）已执行、而 V34（DROP *__bak）尚未执行时，
--           用本脚本把数据库回滚到 V32 完成态（明文列恢复 + 原索引/约束恢复）。
-- 依据：shared/test-plan/16-pii-w8-shrink-plan.md §5.2（V33 rename 可逆，反向 RENAME 即回）。
--
-- ⚠ V34 已执行后本脚本无效（__bak 列已被 DROP 销毁）——唯一恢复手段是
--   backup_w8_gap_delete_20260901.sql 全库备份还原（演练见 shared/ops/restore-drill-w8.py）。
--
-- MySQL 8 方言（与 V33__pii_shrink_rename__mysql.sql 镜像；H2 测试库无需回滚脚本）。
-- 幂等性说明：本脚本假设库正处于"V33 后 V34 前"状态；重复执行会因列/索引不存在报错，
--   与 V33/V34 的 repair 幂等设计不同（回滚脚本不承诺幂等，一次有效）。

-- 1) 8 列 RENAME 回原名（先于索引重建，索引引用原名列）
ALTER TABLE `users` RENAME COLUMN `phone__bak` TO `phone`;
ALTER TABLE `users` RENAME COLUMN `phone_hash__bak` TO `phone_hash`;
ALTER TABLE `sms_codes` RENAME COLUMN `phone__bak` TO `phone`;
ALTER TABLE `tenants` RENAME COLUMN `contact_phone__bak` TO `contact_phone`;
ALTER TABLE `tenant_applications` RENAME COLUMN `contact_phone__bak` TO `contact_phone`;
ALTER TABLE `wholesaler_applications` RENAME COLUMN `contact_phone__bak` TO `contact_phone`;
ALTER TABLE `inquiry_requests` RENAME COLUMN `rt_phone__bak` TO `rt_phone`;
ALTER TABLE `customer_prices` RENAME COLUMN `rt_phone__bak` TO `rt_phone`;

-- 2) 恢复 V33 前置 DROP 的 3 个旧索引/约束（定义回源 V1/V9）
ALTER TABLE `users` ADD UNIQUE KEY `uk_phone_hash` (`phone_hash`);
ALTER TABLE `customer_prices` ADD UNIQUE KEY `uk_custprice_wh_phone_sku` (`wholesaler_id`, `rt_phone`, `sku_id`);
ALTER TABLE `customer_prices` ADD INDEX `idx_custprice_phone` (`rt_phone`);
