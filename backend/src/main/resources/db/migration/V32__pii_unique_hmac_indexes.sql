-- V32: PII-W8（16-pii-w8-shrink-plan §1.4.1，设计 15 §4 V28 的补做项）——hmac 盲索引升 UNIQUE。
--
-- ⚠️ 去重闸门（执行 V32 前必须逐条跑，任一返回 >0 行即停本迁移，先修数据再升 UNIQUE；本迁移不修数据）：
--
--   1) users（R1 规范化漂移：同号不同空白格式 sha256 不同但 hmac 相同）：
--        SELECT phone_hmac, COUNT(*) FROM `users`
--        GROUP BY phone_hmac HAVING COUNT(*) > 1;                          -- 必须 0 行
--   2) customer_prices（R3 脏数据）：
--        SELECT wholesaler_id, rt_phone_hmac, sku_id, COUNT(*) FROM `customer_prices`
--        GROUP BY wholesaler_id, rt_phone_hmac, sku_id HAVING COUNT(*) > 1; -- 必须 0 行
--   3) blacklist（PHONE 行物理唯一；LICENSE_NO 行 target_value_hmac=NULL，
--      MySQL 唯一索引允许多个 NULL，天然豁免）：
--        SELECT target_type, target_value_hmac, COUNT(*) FROM `blacklist`
--        WHERE target_type = 'PHONE'
--        GROUP BY target_type, target_value_hmac HAVING COUNT(*) > 1;       -- 必须 0 行
--
-- 命名核实：V27/V30 实际创建的普通索引名分别为 idx_users_phone_hmac /
-- idx_blacklist_type_hmac / idx_customer_prices_ws_hmac_sku，与 16 §1.4.1 完全一致，
-- 本迁移直接 DROP 这三个普通索引，无改名偏差。
-- 可逆性：删除 UNIQUE 索引即回退为普通索引（纯 DDL，删索引无数据损失）。
-- H2(MODE=MySQL) 兼容：DROP INDEX ... ON 与 CREATE UNIQUE INDEX ... ON 两库均支持。

-- 1) users：登录命门（原 uk_phone_hash 的唯一性由 uk_phone_hmac 承接，V34 将 DROP uk_phone_hash）
DROP INDEX `idx_users_phone_hmac` ON `users`;
CREATE UNIQUE INDEX `uk_phone_hmac` ON `users` (`phone_hmac`);

-- 2) blacklist：PHONE 行物理唯一（LICENSE_NO 行 hmac=NULL，唯一键允许多 NULL）
DROP INDEX `idx_blacklist_type_hmac` ON `blacklist`;
CREATE UNIQUE INDEX `uk_blacklist_type_hmac` ON `blacklist` (`target_type`, `target_value_hmac`);

-- 3) customer_prices：定价身份唯一（承接 uk_custprice_wh_phone_sku，V34 将 DROP 旧明文键）
DROP INDEX `idx_customer_prices_ws_hmac_sku` ON `customer_prices`;
CREATE UNIQUE INDEX `uk_custprice_wh_hmac_sku` ON `customer_prices` (`wholesaler_id`, `rt_phone_hmac`, `sku_id`);
