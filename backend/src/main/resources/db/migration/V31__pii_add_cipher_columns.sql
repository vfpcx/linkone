-- V31: PII-W8 前置（16-pii-w8-shrink-plan §1.6）——补 AES-GCM 密文列 / 尾号摘要列。
--
-- 口径与 V27/V30 逐条对齐：
--   * 全部 VARCHAR NULL（存量行 cipher/last4 恒 NULL，靠 PiiBackfillService 回填）；
--   * 本迁移【不建索引】——cipher 是解密后才能用的全号列，按明文检索无意义，
--     唯一键/索引调整（含 last4 前缀索引）留待 B3/B4 与 rename/收缩一起做（16 §1.6）；
--   * 纯 additive，无回滚脚本——write-mode 拨回 legacy 即止血，新列数据留存无害。
--   * 明文列原样保留（V33 rename 在 B3），本迁移上线后写切点在 isDualWrite() 分支内
--     同写「明文 + hmac + cipher/last4」三段并存。
--
-- 文件名不得改动（Flyway checksum）。

-- 1) users：查全号唯一来源（V34 收缩后）+ 尾号摘要（列表/日志免解密打码）。
ALTER TABLE `users` ADD COLUMN `phone_cipher` VARCHAR(256) NULL COMMENT 'AES-GCM(phone) 密文 Base64(iv||ct||tag)';
ALTER TABLE `users` ADD COLUMN `phone_last4` VARCHAR(4) NULL COMMENT 'phone 尾号 4 位';

-- 2) sms_codes：无全号消费点，只加 last4 摘要（16 §1.6）。
ALTER TABLE `sms_codes` ADD COLUMN `phone_last4` VARCHAR(4) NULL COMMENT 'phone 尾号 4 位';

-- 3) tenants / tenant_applications / wholesaler_applications：入驻链 contact_phone 密文。
ALTER TABLE `tenants` ADD COLUMN `contact_phone_cipher` VARCHAR(256) NULL COMMENT 'AES-GCM(contact_phone) 密文';
ALTER TABLE `tenant_applications` ADD COLUMN `contact_phone_cipher` VARCHAR(256) NULL COMMENT 'AES-GCM(contact_phone) 密文';
ALTER TABLE `wholesaler_applications` ADD COLUMN `contact_phone_cipher` VARCHAR(256) NULL COMMENT 'AES-GCM(contact_phone) 密文';

-- 4) inquiry_requests：RT 询价单的客户全号密文（reveal INQUIRY / settle 透传来源）。
ALTER TABLE `inquiry_requests` ADD COLUMN `rt_phone_cipher` VARCHAR(256) NULL COMMENT 'AES-GCM(rt_phone) 密文';

-- 5) blacklist：仅 PHONE 行写 cipher，LICENSE_NO 行恒 NULL（同 hmac 口径）。
ALTER TABLE `blacklist` ADD COLUMN `target_value_cipher` VARCHAR(256) NULL COMMENT 'AES-GCM(target_value) 密文（仅 PHONE 行）';

-- 6) customer_prices：身份键走 hmac，无全号消费点，故【不加 cipher】（16 §1.6 明确）；
--    按产品 D4 衍生需求追加 last4 摘要——列表打码展示「****1234」用，非全号、不建索引。
ALTER TABLE `customer_prices` ADD COLUMN `rt_phone_last4` VARCHAR(4) NULL COMMENT 'rt_phone 尾号 4 位（D4 展示摘要，非全号）';
