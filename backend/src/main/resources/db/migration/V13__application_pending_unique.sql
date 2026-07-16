-- V13: P2 入驻 审查修复 F1（BLOCKER）——「一账号仅一 PENDING 申请」数据库级唯一约束
-- 原实现为先查后写（doCreatePending 内 selectCount→insert），并发窗口内可双双通过检查
-- 造成同一账号两条 PENDING（CON-S7-01）。改为部分唯一索引模式：
--   pending_flag：PENDING=1，终态（APPROVED/REJECTED）置 NULL；
--   uk_applicant_pending(applicant_user_id, pending_flag)：NULL 不参与唯一冲突
--   （MySQL/H2 一致语义），故任意多终态申请共存、PENDING 至多一条，插入冲突由
--   DuplicateKeyException 捕获转 50201。

ALTER TABLE `wholesaler_applications`
    ADD COLUMN `pending_flag` TINYINT NULL COMMENT 'PENDING=1/终态 NULL（uk_applicant_pending 部分唯一，F1）';

-- 存量回填：现有 PENDING 置 1（若历史脏数据已存在同账号双 PENDING，建索引会失败——
-- 属须人工介入的数据问题，宁可迁移失败也不静默放过）
UPDATE `wholesaler_applications` SET `pending_flag` = 1 WHERE `status` = 'PENDING' AND `deleted_at` IS NULL;

ALTER TABLE `wholesaler_applications`
    ADD UNIQUE KEY `uk_applicant_pending` (`applicant_user_id`, `pending_flag`);
