package com.cangchu.common.config;

import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resolver.sql.SqlMigrationResolver;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flyway database-specific 迁移解析器（V33/V34 的 {@code __mysql}/{@code __h2} 拆分落地）。
 *
 * <p>背景：16-pii-w8-shrink-plan §1.3（9/1 架构师裁决）要求 V33/V34 按
 * {@code V33__pii_shrink_rename__mysql.sql}/{@code __h2.sql} 命名拆分。但 Flyway
 * Community 11.7.2 实测<b>不识别</b>文件名 {@code __db} 后缀（B3 探针：仅两个带后缀文件并存时
 * 报 "Found more than one migration with version 33"，Teams 版专属能力 Community 不可用）。
 *
 * <p>本解析器委托内置 {@link SqlMigrationResolver} 解析全部 SQL 迁移，再按当前数据库类型
 * 过滤掉非本库的 {@code __db} 变体，使上述命名在 Community 版可用。注意必须配合
 * {@code skipDefaultResolvers(true)} 使用（见 {@link FlywayConfig}），否则默认解析器仍会把
 * 两个变体同时解析出来导致版本冲突。
 *
 * <p>过滤规则：仅对文件名末尾形如 {@code __{db}.sql} 的已知数据库标识符做过滤；
 * 无后缀或后缀不属于已知数据库名的文件一律放行（V1–V32 历史迁移不受影响）。
 * 若后续新增 Java 迁移（{@code JavaMigrationResolver}），需要在本解析器中一并委托。
 */
public class FlywayDatabaseSpecificResolver implements MigrationResolver {

    /** 已知数据库标识符（文件名 {@code __db} 后缀只对它们做过滤）。 */
    private static final Set<String> KNOWN_DB_NAMES = Set.of(
            "h2", "mysql", "mariadb", "postgresql", "postgres", "oracle",
            "sqlserver", "sqlite", "db2", "derby", "hsql", "cockroachdb");

    @Override
    public Collection<ResolvedMigration> resolveMigrations(Context context) {
        String currentDb = context.configuration.getDatabaseType().getName().toLowerCase(Locale.ROOT);

        SqlMigrationResolver delegate = new SqlMigrationResolver(
                context.resourceProvider,
                context.sqlScriptExecutorFactory,
                context.sqlScriptFactory,
                context.configuration,
                new ParsingContext());

        List<ResolvedMigration> all = delegate.resolveMigrations(context);
        return all.stream()
                .filter(m -> accepts(m.getScript(), currentDb))
                .toList();
    }

    private boolean accepts(String script, String currentDb) {
        if (script == null) {
            return true;
        }
        String lower = script.toLowerCase(Locale.ROOT);
        int idx = lower.lastIndexOf("__");
        if (idx < 0) {
            return true;
        }
        String tail = lower.substring(idx + 2); // 如 "mysql.sql"
        int dot = tail.lastIndexOf('.');
        String suffix = dot >= 0 ? tail.substring(0, dot) : tail;
        if (suffix.isEmpty()) {
            return true;
        }
        // 非已知库名 → 视为描述的一部分（如 V2__init_account.sql 的尾段），放行
        if (!KNOWN_DB_NAMES.contains(suffix)) {
            return true;
        }
        // 已知库名 → 仅当前库的变体放行
        return suffix.equals(currentDb);
    }

    /** 供冒烟测试/独立 harness 复用：直接构造解析器（无需 Spring）。 */
    public static FlywayDatabaseSpecificResolver create() {
        return new FlywayDatabaseSpecificResolver();
    }
}
