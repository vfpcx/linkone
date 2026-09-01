-- V37: 一账号多仓（H2(MODE=MySQL) 变体）
--
-- 对应 2026-09-01 产品决策（一账号可入驻多仓），变更内容与 MySQL 变体完全一致（终态一致）。
-- H2 2.x(MODE=MySQL) 方言：V13 内联 `UNIQUE KEY uk_applicant_pending (...)` 的唯一约束，其支撑
-- 索引名是自动生成的（V33 实测：不能按声明名 DROP INDEX，必须按声明名 DROP CONSTRAINT）；
-- 重建沿用 V13 同款内联 `UNIQUE KEY` 语法（H2 接受，既有测试已验证）。

ALTER TABLE `wholesaler_applications` DROP CONSTRAINT IF EXISTS `uk_applicant_pending`;
ALTER TABLE `wholesaler_applications`
    ADD UNIQUE KEY `uk_applicant_pending` (`applicant_user_id`, `tenant_id`, `pending_flag`);
