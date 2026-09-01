package com.cangchu.common.pii;

import com.cangchu.common.config.FlywayDatabaseSpecificResolver;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * W8-B3 MySQL 侧验证（main 方法直跑，不经 JUnit；需本地 MySQL localhost:3306 root/root）：
 * 在隔离 schema {@code probe_v33v34} 上，用 Flyway + {@link FlywayDatabaseSpecificResolver}
 * 全链执行 V1–V34（自动挑选 {@code __mysql} 变体），然后与 H2 冒烟测试
 * {@link V33V34H2MigrationSmokeTest} 的**同一终态断言集**比对：
 * <ul>
 *   <li>8 个明文列消失（phone/phone_hash/contact_phone/rt_phone 及其 __bak）</li>
 *   <li>3 个旧索引/约束消失（uk_phone_hash / uk_custprice_wh_phone_sku / idx_custprice_phone）</li>
 *   <li>3 个新唯一索引生效（uk_phone_hmac / uk_custprice_wh_hmac_sku / uk_blacklist_type_hmac）</li>
 *   <li>blacklist PHONE 摘要 {@code PHONE_****{last4}}，碰撞行追加 {@code :{hmac4}}；LICENSE_NO 不动</li>
 *   <li>repair 重跑 V34 8.1/8.2 → blacklist 零变化（幂等守卫生效）</li>
 * </ul>
 * 与 H2 冒烟测试断言一致 ⇒ 「两库终态完全一致」（16-pii-w8-shrink-plan §1.3/R7）。
 * 使用后 DROP 探针 schema（不改动任何业务库）。
 */
public final class MySQLDialectProbe {

    private static final String URL =
            "jdbc:mysql://localhost:3306/cangchu_dev?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectionCollation=utf8mb4_unicode_ci";
    private static final String PROBE_DB = "probe_v33v34";
    private static final String MIGRATION_DIR =
            "d:/chenxu/my-linkone/superpowers-collab/backend/src/main/resources/db/migration";

    private static int failures = 0;

    private MySQLDialectProbe() {
    }

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(URL, "root", "root")) {
            System.out.println("MySQL version: " + query(c, "SELECT VERSION()"));

            try (Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + PROBE_DB);
                st.execute("CREATE DATABASE " + PROBE_DB);
            } catch (SQLException e) {
                System.out.println("[FATAL] 无建库权限，中止（避免污染业务库）: " + e.getMessage());
                return;
            }

            String probeUrl = URL.replace("cangchu_dev", PROBE_DB);

            // 分段迁移：先 V1–V32（明文列仍在，便于造数），seed blacklist 后再跑 V33/V34
            // （V34 内部完成摘要改写，与 H2 冒烟测试同构）
            Flyway.configure()
                    .dataSource(probeUrl, "root", "root")
                    .locations("filesystem:" + MIGRATION_DIR)
                    .resolvers(FlywayDatabaseSpecificResolver.create())
                    .skipDefaultResolvers(true)
                    .target("32")
                    .load()
                    .migrate();

            try (Connection pc = DriverManager.getConnection(probeUrl, "root", "root")) {
                seedBlacklist(pc);

                Flyway.configure()
                        .dataSource(probeUrl, "root", "root")
                        .locations("filesystem:" + MIGRATION_DIR)
                        .resolvers(FlywayDatabaseSpecificResolver.create())
                        .skipDefaultResolvers(true)
                        .load()
                        .migrate();
                System.out.println("V33/V34 (__mysql 变体) migrate OK");

                // 幂等性：repair 重跑 V34 的 8.1/8.2（与迁移文件逐字一致）→ blacklist 零变化
                String before = query(pc,
                        "SELECT GROUP_CONCAT(target_type, '|', target_value ORDER BY id SEPARATOR ';') FROM blacklist");
                execute(pc, update81());
                execute(pc, update82());
                String after = query(pc,
                        "SELECT GROUP_CONCAT(target_type, '|', target_value ORDER BY id SEPARATOR ';') FROM blacklist");
                if (!before.equals(after)) {
                    fail("repair 重跑后 blacklist 值变化（幂等守卫失效）");
                } else {
                    System.out.println("repair 重跑 blacklist 零变化（幂等守卫生效）");
                }

                assertFinalState(pc);
            }
        }
        System.out.println(failures == 0 ? "RESULT: ALL-PASS" : "RESULT: FAILURES=" + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---- 与 H2 冒烟测试相同的终态断言集 ----

    private static void assertFinalState(Connection c) throws SQLException {
        for (String col : new String[]{"phone", "phone_hash", "phone__bak", "phone_hash__bak"}) {
            assertColumnGone(c, "users", col);
        }
        assertColumnGone(c, "sms_codes", "phone");
        assertColumnGone(c, "sms_codes", "phone__bak");
        assertColumnGone(c, "tenants", "contact_phone");
        assertColumnGone(c, "tenants", "contact_phone__bak");
        assertColumnGone(c, "tenant_applications", "contact_phone");
        assertColumnGone(c, "tenant_applications", "contact_phone__bak");
        assertColumnGone(c, "wholesaler_applications", "contact_phone");
        assertColumnGone(c, "wholesaler_applications", "contact_phone__bak");
        assertColumnGone(c, "inquiry_requests", "rt_phone");
        assertColumnGone(c, "inquiry_requests", "rt_phone__bak");
        assertColumnGone(c, "customer_prices", "rt_phone");
        assertColumnGone(c, "customer_prices", "rt_phone__bak");

        for (String col : new String[]{"phone_cipher", "phone_last4", "phone_hmac"}) {
            assertColumn(c, "users", col);
        }
        assertColumn(c, "blacklist", "target_value_cipher");

        for (String idx : new String[]{"uk_phone_hash", "uk_custprice_wh_phone_sku", "idx_custprice_phone"}) {
            assertIndexGone(c, idx);
        }
        for (String idx : new String[]{"uk_phone_hmac", "uk_custprice_wh_hmac_sku", "uk_blacklist_type_hmac"}) {
            assertUniqueIndex(c, idx);
        }

        assertValue(c, "blacklist", 1L, "PHONE_****1234");
        assertValue(c, "blacklist", 2L, "PHONE_****5678");
        assertValue(c, "blacklist", 3L, "PHONE_****1234:CCCC");
        assertValue(c, "blacklist", 4L, "PHONE_****1234:DDDD");
        assertValue(c, "blacklist", 5L, "SH-2026-0001");
        System.out.println("MySQL 侧终态断言全部通过（与 H2 冒烟测试同一断言集）");
    }

    private static void seedBlacklist(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO blacklist (id, target_type, target_value, target_value_hmac, reason, operator_user_id, status) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 'ACTIVE')")) {
            ps.setLong(1, 1L); ps.setString(2, "PHONE"); ps.setString(3, "13800001234");
            ps.setString(4, "hmacAAAA"); ps.setString(5, "r1"); ps.executeUpdate();
            ps.setLong(1, 2L); ps.setString(2, "PHONE"); ps.setString(3, "13800005678");
            ps.setString(4, "hmacBBBB"); ps.setString(5, "r2"); ps.executeUpdate();
            ps.setLong(1, 3L); ps.setString(2, "PHONE"); ps.setString(3, "13911111234");
            ps.setString(4, "hmacCCCC"); ps.setString(5, "r3"); ps.executeUpdate();
            ps.setLong(1, 4L); ps.setString(2, "PHONE"); ps.setString(3, "13922221234");
            ps.setString(4, "hmacDDDD"); ps.setString(5, "r4"); ps.executeUpdate();
            ps.setLong(1, 5L); ps.setString(2, "LICENSE_NO"); ps.setString(3, "SH-2026-0001");
            ps.setNull(4, java.sql.Types.VARCHAR); ps.setString(5, "r5"); ps.executeUpdate();
        }
    }

    /** V34 mysql 变体 8.1（UPDATE JOIN + 幂等守卫），与迁移文件逐字一致。 */
    private static String update81() {
        return "UPDATE `blacklist` b "
                + "JOIN (SELECT RIGHT(`target_value`, 4) AS last4, COUNT(*) AS cnt FROM `blacklist` "
                + "WHERE `target_type` = 'PHONE' GROUP BY RIGHT(`target_value`, 4)) g "
                + "ON g.last4 = RIGHT(b.`target_value`, 4) "
                + "SET b.`target_value` = CONCAT('PHONE_****', RIGHT(b.`target_value`, 4)) "
                + "WHERE b.`target_type` = 'PHONE' AND g.cnt = 1 AND b.`target_value` NOT LIKE 'PHONE_****%'";
    }

    /** V34 mysql 变体 8.2（UPDATE JOIN + 幂等守卫），与迁移文件逐字一致。 */
    private static String update82() {
        return "UPDATE `blacklist` b "
                + "JOIN (SELECT RIGHT(`target_value`, 4) AS last4, COUNT(*) AS cnt FROM `blacklist` "
                + "WHERE `target_type` = 'PHONE' GROUP BY RIGHT(`target_value`, 4)) g "
                + "ON g.last4 = RIGHT(b.`target_value`, 4) "
                + "SET b.`target_value` = CONCAT('PHONE_****', RIGHT(b.`target_value`, 4), ':', RIGHT(b.`target_value_hmac`, 4)) "
                + "WHERE b.`target_type` = 'PHONE' AND g.cnt > 1 AND b.`target_value` NOT LIKE 'PHONE_****%'";
    }

    // ---- helpers ----

    private static void assertColumn(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '" + PROBE_DB
                        + "' AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table); ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    fail("列缺失: " + table + "." + column);
                }
            }
        }
    }

    private static void assertColumnGone(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '" + PROBE_DB
                        + "' AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table); ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    fail("列应已删除但仍存在: " + table + "." + column);
                }
            }
        }
    }

    private static void assertIndexGone(Connection c, String index) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = '" + PROBE_DB
                        + "' AND INDEX_NAME = ?")) {
            ps.setString(1, index);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    fail("索引应已删除但仍存在: " + index);
                }
            }
        }
    }

    private static void assertUniqueIndex(Connection c, String index) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT NON_UNIQUE FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = '" + PROBE_DB
                        + "' AND INDEX_NAME = ? LIMIT 1")) {
            ps.setString(1, index);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    fail("唯一索引缺失: " + index);
                    return;
                }
                if (rs.getInt(1) != 0) {
                    fail("索引非唯一: " + index);
                }
            }
        }
    }

    private static void assertValue(Connection c, String table, long id, String expected) throws SQLException {
        String actual = query(c, "SELECT target_value FROM " + table + " WHERE id = " + id);
        if (!expected.equals(actual)) {
            fail(table + " id=" + id + " 期望 [" + expected + "] 实际 [" + actual + "]");
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static String query(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("FAIL: " + msg);
    }
}
