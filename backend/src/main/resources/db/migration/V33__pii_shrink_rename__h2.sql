-- V33: PII-W8 明文收缩 · 窗口 1（可逆段）：明文列 RENAME 为 *__bak（H2(MODE=MySQL) 变体）
--
-- 对应 16-pii-w8-shrink-plan §1.3（数据库特定拆分，9/1 架构师裁决）。
-- H2 2.x(MODE=MySQL) 方言：V1/V9 内联 `UNIQUE KEY xxx (...)` 的唯一约束，其支撑索引名是
-- **自动生成**的（实测 UK_PHONE_HASH_INDEX_3 / UK_CUSTPRICE_WH_PHONE_SKU_INDEX_6），不能按
-- 声明名 DROP INDEX（not found）——必须 DROP CONSTRAINT（声明名，B3 实测通过）；
-- 普通索引 idx_custprice_phone 保留声明名，可直接 DROP INDEX（H2 风格，不带 ON）。
--
-- 变更内容与 MySQL 变体完全一致（终态一致，16 §1.3/R7）：
--   * rename 前先 DROP 3 个旧索引/约束（uk_phone_hash、uk_custprice_wh_phone_sku、
--     idx_custprice_phone——唯一性已由 V32 uk_phone_hmac / uk_custprice_wh_hmac_sku 承接）；
--   * 8 列 RENAME 为 __bak 后缀（保留数据，V34 再 DROP）；
--   * blacklist.target_value 不 rename（保留供 LICENSE_NO 行，见 16 §1.5）。
--
-- 闸门（发布前置，8.1 last4/cipher 回填闸门全绿才允许执行本迁移）：16 §3.2 / §8.1。
-- 回滚口径：rename 可逆——反向 RENAME 即回（V33 秒级回滚），见 16 §5.2。

-- 1) 旧索引/约束 DROP（先于 rename；H2 唯一约束按声明名 DROP CONSTRAINT）
ALTER TABLE `users` DROP CONSTRAINT IF EXISTS `uk_phone_hash`;
ALTER TABLE `customer_prices` DROP CONSTRAINT IF EXISTS `uk_custprice_wh_phone_sku`;
DROP INDEX IF EXISTS `idx_custprice_phone`;

-- 2) 8 列 RENAME（H2 2.x MySQL 模式接受 MySQL 8 的 RENAME COLUMN 语法）
ALTER TABLE `users` RENAME COLUMN `phone` TO `phone__bak`;
ALTER TABLE `users` RENAME COLUMN `phone_hash` TO `phone_hash__bak`;
ALTER TABLE `sms_codes` RENAME COLUMN `phone` TO `phone__bak`;
ALTER TABLE `tenants` RENAME COLUMN `contact_phone` TO `contact_phone__bak`;
ALTER TABLE `tenant_applications` RENAME COLUMN `contact_phone` TO `contact_phone__bak`;
ALTER TABLE `wholesaler_applications` RENAME COLUMN `contact_phone` TO `contact_phone__bak`;
ALTER TABLE `inquiry_requests` RENAME COLUMN `rt_phone` TO `rt_phone__bak`;
ALTER TABLE `customer_prices` RENAME COLUMN `rt_phone` TO `rt_phone__bak`;
