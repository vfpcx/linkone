-- V27: PII 硬化阶段 0（15-pii-hardening-v2 §4 阶段0 / §2）——加列，全部 NULLable，先建普通索引。
-- 唯一索引升级（uk_phone_hmac / uk_blacklist_type_hmac）留待 V28：回填核验三件套通过后才升 UNIQUE。
-- 回滚口径：本迁移纯 additive，write-mode 拨回 legacy 即止血，新列数据留存无害，无需回滚。

-- 1) users：登录命门（uk_phone_hash）的 HMAC 盲索引影子列。
--    阶段 0 只双写+回填，读路径（phone_hash）一律不动。
ALTER TABLE `users` ADD COLUMN `phone_hmac` VARCHAR(64) NULL COMMENT 'HMAC-SHA256(phone) 盲索引（阶段0双写，读仍走 phone_hash）';
CREATE INDEX `idx_users_phone_hmac` ON `users` (`phone_hmac`);

-- 2) blacklist：双键表（PHONE/LICENSE_NO）。仅 PHONE 行回填/双写 hmac，
--    LICENSE_NO 行 hmac 恒 NULL 保留明文分支（15 §2-1；MySQL 唯一索引对 NULL 不去重，
--    V28 升 UNIQUE 后 LICENSE_NO 行天然豁免）。
ALTER TABLE `blacklist` ADD COLUMN `target_value_hmac` VARCHAR(64) NULL COMMENT 'HMAC-SHA256(target_value) 盲索引（仅 PHONE 行，LICENSE_NO 恒 NULL）';
CREATE INDEX `idx_blacklist_type_hmac` ON `blacklist` (`target_type`, `target_value_hmac`);
