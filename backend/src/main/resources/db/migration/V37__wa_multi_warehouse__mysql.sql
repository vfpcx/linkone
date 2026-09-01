-- V37: 一账号多仓（2026-09-01 产品决策）——uk_applicant_pending 唯一索引按 (账号, 目标租户) 维度
--
-- 背景：手机号 = 账号唯一标识，一个批发商账号可入驻多个仓库（不再支持「同手机号重复注册
-- 不同账号」进多仓，05 §6.3 同步修订）。入驻申请侧仅需放开「同账号对多仓同时 PENDING」：
--   * 原索引 uk_applicant_pending(applicant_user_id, pending_flag)：一个账号全平台至多 1 条 PENDING；
--   * 新索引 (applicant_user_id, tenant_id, pending_flag)：同账号对不同仓库可各有 1 条 PENDING，
--     同仓库仍至多 1 条（与 doCreatePending 的 selectCount 按租户计数一致）。
--
-- 安全性：多仓放开前，原索引保证「一个账号全平台至多 1 条 PENDING」，故同 (账号, 租户) 组合
-- 天然唯一，DROP 后按新维度重建不会产生冲突（迁移必成功，无需人工数据修复）。
-- 回滚口径：DROP 新索引 → 重建旧索引即可（幂等安全，同 V13 终态）。
--
-- 变更内容与 H2 变体完全一致（终态一致）。

ALTER TABLE `wholesaler_applications` DROP INDEX `uk_applicant_pending`;
ALTER TABLE `wholesaler_applications`
    ADD UNIQUE KEY `uk_applicant_pending` (`applicant_user_id`, `tenant_id`, `pending_flag`);
