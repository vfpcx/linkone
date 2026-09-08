# shared/ops · 运维工具与脚本（入库共享）

PII-S2（W8）/ 发布窗口相关的可复用运维脚本。所有脚本均为**只读源库或仅在隔离资源上写入**，
执行前请阅读脚本头注释与适用窗口。

| 文件 | 用途 | 适用窗口/依据 |
|---|---|---|
| `restore-drill-w8.py` | W8-L1 还原演练：把 `backup_w8_gap_delete_20260901.sql`（37 表 27700 行 INSERT）还原到临时库 `restore_drill_w8_*`，做行数对比 + PII 8 表全列逐行值比对，演练后默认删库 | V34 不可逆段唯一恢复手段的演练（`13-pii-w8-delivery-report.md` §8.5/§10.3） |
| `v33-reverse-rename.sql` | V33 反向回滚：8 个 `*__bak` 列 rename 回明文原名 + 恢复 3 个旧索引/约束（MySQL 8） | 仅 V33 后 V34 前窗口可用（`16-pii-w8-shrink-plan.md` §5.2）；V34 后须走备份还原 |
| `cve-scan.ps1` | W8-L4 CVE 复扫工具门禁（2026-09-08 F7-6 命令化）：三路 = 后端 OWASP dep-check（Maven 插件，不改 pom）+ Trivy fs（backend/pom.xml）+ osv-scanner（frontend/apps/admin）；报告归档 `backend/target/cve-scan/<ts>/`（不入库）；工具缺失标 WARN 不阻塞、有漏洞标 FAIL | 上线前工具门禁（`06-dependency-cve-scan.md` §7.4），正式环境/工具装好后执行 |
| `go-live-checklist.md` | 上线检查单命令化（2026-09-08 F7-6）：CVE 门禁 / prod 冒烟（fail-fast 负路径 + 三链路）/ graceful shutdown 停服实测 / Redis 密码+ACL（W8-L2~L6 余项）逐项命令 + 期望 + 状态回填位 | 上线前（`13-pii-w8-delivery-report.md` §8.5 / `11-hardening-design.md` §2.2 / `application-prod.yml`） |

## 本地演练速记

```powershell
# dry-run 解析自检（不连库）
python shared/ops/restore-drill-w8.py --dry-run
# 完整演练（密码取 backend/src/main/resources/application-local.yml）
python shared/ops/restore-drill-w8.py --password <pwd>
# 子集演练（快速验证链路）
python shared/ops/restore-drill-w8.py --password <pwd> --only users,blacklist
# 演练后保留临时库供人工复核
python shared/ops/restore-drill-w8.py --password <pwd> --keep
```
