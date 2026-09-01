-- V33: PII-W8 明文收缩 · 窗口 1（可逆段）：明文列 RENAME 为 *__bak（MySQL 变体）
--
-- 对应 16-pii-w8-shrink-plan §1.3（数据库特定拆分，9/1 架构师裁决）。
-- MySQL 8 方言：唯一约束的支撑索引沿用声明名（V1 uk_phone_hash / V9 uk_custprice_wh_phone_sku），
-- 直接 DROP INDEX；普通索引 idx_custprice_phone 同样保留声明名。RENAME COLUMN 为 MySQL 8 语法。
--
-- 变更内容：
--   * rename 前先 DROP 3 个旧索引/约束（uk_phone_hash、uk_custprice_wh_phone_sku 的
--     唯一性已由 V32 uk_phone_hmac / uk_custprice_wh_hmac_sku 承接；idx_custprice_phone 无对应物，弃）；
--   * 8 列 RENAME 为 __bak 后缀（保留数据，V34 再 DROP）：users.phone/phone_hash、
--     sms_codes.phone、tenants.contact_phone、tenant_applications.contact_phone、
--     wholesaler_applications.contact_phone、inquiry_requests.rt_phone、customer_prices.rt_phone；
--   * blacklist.target_value 不 rename（保留供 LICENSE_NO 行，见 16 §1.5）。
--
-- 闸门（发布前置，8.1 last4/cipher 回填闸门全绿才允许执行本迁移）：16 §3.2 / §8.1。
-- 回滚口径：rename 可逆——反向 RENAME 即回（V33 秒级回滚），见 16 §5.2。

-- 1) 旧索引/约束 DROP（先于 rename，避免 rename 波及索引列导致重建）
ALTER TABLE `users` DROP INDEX `uk_phone_hash`;
ALTER TABLE `customer_prices` DROP INDEX `uk_custprice_wh_phone_sku`;
ALTER TABLE `customer_prices` DROP INDEX `idx_custprice_phone`;

-- 2) 8 列 RENAME
ALTER TABLE `users` RENAME COLUMN `phone` TO `phone__bak`;
ALTER TABLE `users` RENAME COLUMN `phone_hash` TO `phone_hash__bak`;
ALTER TABLE `sms_codes` RENAME COLUMN `phone` TO `phone__bak`;
ALTER TABLE `tenants` RENAME COLUMN `contact_phone` TO `contact_phone__bak`;
ALTER TABLE `tenant_applications` RENAME COLUMN `contact_phone` TO `contact_phone__bak`;
ALTER TABLE `wholesaler_applications` RENAME COLUMN `contact_phone` TO `contact_phone__bak`;
ALTER TABLE `inquiry_requests` RENAME COLUMN `rt_phone` TO `rt_phone__bak`;
ALTER TABLE `customer_prices` RENAME COLUMN `rt_phone` TO `rt_phone__bak`;
