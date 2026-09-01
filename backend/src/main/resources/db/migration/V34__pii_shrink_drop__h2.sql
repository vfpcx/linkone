-- V34: PII-W8 明文收缩 · 窗口 2（不可逆段）：DROP *__bak 列 + blacklist PHONE 摘要改写（H2(MODE=MySQL) 变体）
--
-- 对应 16-pii-w8-shrink-plan §1.4 / §1.5（数据库特定拆分，9/1 架构师裁决）。
-- H2 2.x 方言：不支持 MySQL `UPDATE ... JOIN ... SET`，blacklist 改写用等价相关子查询
-- 计数写法（B3 实测通过），终态与 MySQL 变体完全一致（16 §1.3/R7）。
--
-- 变更内容：
--   * DROP 8 个 __bak 列（V33 rename 的明文数据在此销毁；phone_hash 一并销毁，hmac 读已迁移）；
--   * blacklist 8.1/8.2：PHONE 行 target_value 收敛为摘要 `PHONE_****{last4}`；
--     同 last4 冲突组（>1 行）追加 {hmac 尾 4} 消歧 `PHONE_****{last4}:{hmac4}`。
--
-- 幂等性（repair+重跑）：8.1/8.2 均带 `target_value NOT LIKE 'PHONE_****%'` 守卫——
-- 已改写行跳过，避免把消歧后缀尾 4 误当 last4 再改写导致撞 uk_blacklist_type_value 唯一键
-- （team-lead 9/1 补强；单跑与 repair 重跑均安全，16 §1.5 设计不变）。
--
-- 回滚口径：不可逆（DROP 列）——需 V33 前备份恢复，见 16 §5.3。

-- 1) DROP 8 个 __bak 列
ALTER TABLE `users` DROP COLUMN `phone__bak`;
ALTER TABLE `users` DROP COLUMN `phone_hash__bak`;
ALTER TABLE `sms_codes` DROP COLUMN `phone__bak`;
ALTER TABLE `tenants` DROP COLUMN `contact_phone__bak`;
ALTER TABLE `tenant_applications` DROP COLUMN `contact_phone__bak`;
ALTER TABLE `wholesaler_applications` DROP COLUMN `contact_phone__bak`;
ALTER TABLE `inquiry_requests` DROP COLUMN `rt_phone__bak`;
ALTER TABLE `customer_prices` DROP COLUMN `rt_phone__bak`;

-- 2) blacklist PHONE 摘要改写（8.1 无冲突组 → 基础摘要；8.2 冲突组 → 追加 hmac4 消歧）
-- 8.1：last4 唯一（cnt=1）的 PHONE 行 → `PHONE_****{last4}`
UPDATE `blacklist`
SET `target_value` = CONCAT('PHONE_****', RIGHT(`target_value`, 4))
WHERE `target_type` = 'PHONE'
  AND `target_value` NOT LIKE 'PHONE_****%'
  AND (SELECT COUNT(*)
       FROM `blacklist` b2
       WHERE b2.`target_type` = 'PHONE'
         AND RIGHT(b2.`target_value`, 4) = RIGHT(`blacklist`.`target_value`, 4)) = 1;

-- 8.2：last4 冲突（cnt>1）的 PHONE 行 → `PHONE_****{last4}:{hmac4}`（hmac 尾 4 消歧）
UPDATE `blacklist`
SET `target_value` = CONCAT('PHONE_****', RIGHT(`target_value`, 4),
                            ':', RIGHT(`target_value_hmac`, 4))
WHERE `target_type` = 'PHONE'
  AND `target_value` NOT LIKE 'PHONE_****%'
  AND (SELECT COUNT(*)
       FROM `blacklist` b2
       WHERE b2.`target_type` = 'PHONE'
         AND RIGHT(b2.`target_value`, 4) = RIGHT(`blacklist`.`target_value`, 4)) > 1;
