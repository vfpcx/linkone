-- V30: PII 阶段 0 补做——定价/短信/询价三链加 HMAC 盲索引列
-- （15-pii-hardening-v2 §4 阶段0 遗漏项 / task_plan「S1 缺口：定价/短信链尚无 hmac 列」）。
--
-- 背景：§4 阶段0 原列 7 张表，V27 实际只加了 users.phone_hmac 与 blacklist.target_value_hmac，
-- 故 §1.2-C 定价链（C1 settle upsert / C2 价格解析 / C3 批量圈选）与 sms 校验、询价单
-- 进不了阶段 1 影子期。本迁移补齐余下三表，口径与 V27 逐条对齐：
--   * 全部 NULLable（存量行 hmac 恒 NULL，靠 PiiBackfillService 回填）；
--   * 先建普通索引，唯一索引升级留待回填对账三件套通过之后，不在本迁移做；
--   * 纯 additive，无回滚脚本——write-mode 拨回 legacy 即止血，新列数据留存无害。
--
-- 索引列序按「Step 2 切读后要走的查询」设计，与各表现有明文索引一一对应：
--   customer_prices 现有唯一键 (wholesaler_id, rt_phone, sku_id) → hmac 版同序；
--   sms_codes 按 (phone, scene) 取最近一条 → hmac 版同序；
--   inquiry_requests 按 rt_phone 圈选 → 单列。

-- 1) customer_prices：§1.2-C 定价链的客户身份列（客户 = rt_phone）。
ALTER TABLE `customer_prices` ADD COLUMN `rt_phone_hmac` VARCHAR(64) NULL COMMENT 'HMAC-SHA256(rt_phone) 盲索引（阶段0双写，读仍走 rt_phone 明文）';
CREATE INDEX `idx_customer_prices_ws_hmac_sku` ON `customer_prices` (`wholesaler_id`, `rt_phone_hmac`, `sku_id`);

-- 2) sms_codes：短信验证码校验链。
ALTER TABLE `sms_codes` ADD COLUMN `phone_hmac` VARCHAR(64) NULL COMMENT 'HMAC-SHA256(phone) 盲索引（阶段0双写，读仍走 phone 明文）';
CREATE INDEX `idx_sms_codes_hmac_scene` ON `sms_codes` (`phone_hmac`, `scene`);

-- 3) inquiry_requests：RT 询价单的客户身份列（与 customer_prices.rt_phone 同一口径）。
ALTER TABLE `inquiry_requests` ADD COLUMN `rt_phone_hmac` VARCHAR(64) NULL COMMENT 'HMAC-SHA256(rt_phone) 盲索引（阶段0双写，读仍走 rt_phone 明文）';
CREATE INDEX `idx_inquiry_requests_rt_phone_hmac` ON `inquiry_requests` (`rt_phone_hmac`);
