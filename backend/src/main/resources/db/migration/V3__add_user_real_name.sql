-- V3__add_user_real_name.sql
-- D-16: 注册业务字段落库 —— users 增加 real_name（真实姓名），与 nickname（展示昵称）区分。
-- 此前 RegisterDto 不接收 realName，前端把 realName 当 nickname 透传，导致真实姓名丢失。
-- 新增独立列以保证账号数据完整性。
ALTER TABLE `users` ADD COLUMN `real_name` VARCHAR(64) NULL COMMENT '真实姓名（实名，区别于展示昵称 nickname）';
