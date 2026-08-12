# Task Plan · PII 三段式硬化 + P4 遗留收口

> 真源：`architecture/15-pii-hardening-v2.md`（V26 基线实测+八波次）。P4 计划已归档 `shared/archive/task_plan-p4.md`。
> 基线：main=80501da，后端 408 测试/E2E 45 例全绿，V1-V26。

## 阶段

- [完成] **设计定稿**：15-pii-hardening-v2（登录链 6 处 eq/黑名单明文+3 处日志漏网/pricing 明文 Redis 键/sms·login 限流无盐键；密钥 fail-fast+启动 KAT）
- [进行中·并行] **P4 遗留收口**〔fix/p4-leftovers〕：WA 按日端点+PDF 无字体用户可见兜底
- [待办] **PII-S0 加列双写**：V27 加 phone_hmac 列（users/blacklist 双键）+双写+存量回填+KAT；读路径不动（可秒回滚）
- [待办] **PII-S1 影子灰度**：影子双查 7 天对账 Job+登录双读兜底自愈；对账零差异后切主读
- [待办] **PII-S2 收缩+打码**：V29 明文列处置+Redis 键改造+限流键加盐+前端 9 处打码（逐页清单）+E2E 断言更新
- [待办] **验收**：全量+E2E45×2+报告 13；上线检查单余项复核（prod 冒烟/CVE 复扫/graceful shutdown 属部署侧待环境）

## 验证

- 每波 mvn 全量绿（基线 408）+ E2E 全套；登录命门波次须含回滚演练证据；独立复验后合并
