package com.cangchu.common.pii;

import com.cangchu.common.config.FlywayDatabaseSpecificResolver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W8-B3 冒烟：V33/V34 数据库特定拆分（{@code __mysql}/{@code __h2}）在 H2(MODE=MySQL) 上的
 * 终态一致性 + blacklist 摘要改写 + repair 重跑幂等性验证（16-pii-w8-shrink-plan §1.3/§1.5/R7）。
 *
 * <p>Flyway Community 11.7.2 不识别 {@code __db} 后缀（B3 实测），因此本测试与生产一致地
 * 使用 {@link FlywayDatabaseSpecificResolver}（skipDefaultResolvers 后委托内置
 * SqlMigrationResolver 并按库类型过滤变体）——H2 上只应用 {@code __h2} 变体。
 *
 * <p>独立 H2 内存库（与共享的 cangchu_test 完全隔离），分段执行以便「明文列仍存在时造数」：
 * <pre>
 *   setUp:        V1–V32（users.phone 等明文列仍在）
 *   order 1:      seed users/sms_codes → V33（rename 锚点）→ 断言 rename 保留数据 + V31/V32 列与索引
 *   order 2:      seed blacklist → V34（DROP + 摘要改写）→ 断言终态（8 列消失 / 3 旧索引消失 /
 *                 3 新唯一索引生效 / blacklist PHONE 摘要逐值 + LICENSE_NO 不动）
 *   order 3:      repair 重跑 V34 的 8.1/8.2 两条 UPDATE → 断言 blacklist 零变化（幂等守卫）
 *   order 4:      MySQL 变体文件结构等价断言（两库同变更集 → 同一终态；真实 MySQL 由
 *                 MySQLDialectProbe 直跑校验）
 * </pre>
 *
 * <p>B3 实测结论（本测试固化）：
 * <ul>
 *   <li>H2 2.x(MODE=MySQL) 中 UNIQUE KEY 的底层索引名自动生成（UK_*_INDEX_n），不能按声明名
 *       DROP INDEX，必须 DROP CONSTRAINT；MySQL 则按声明名 DROP INDEX——此为拆分根因一。</li>
 *   <li>H2 不支持 UPDATE ... JOIN ... SET，MySQL 拒同一表相关子查询 UPDATE（error 1093）——
 *       两库 blacklist 改写各用自然方言，终态一致——此为拆分根因二。</li>
 *   <li>8.1/8.2 幂等守卫 {@code target_value NOT LIKE 'PHONE_****%'}：repair 重跑跳过已改写行，
 *       避免把消歧后缀尾 4 误当 last4 再改写撞 uk_blacklist_type_value 唯一键。</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V33V34H2MigrationSmokeTest {

    private static final String URL =
            "jdbc:h2:mem:w8_migration_smoke;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";

    private static Connection conn;

    private static Flyway flywayTo(String target) {
        return Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("classpath:db/migration")
                .resolvers(FlywayDatabaseSpecificResolver.create())
                .skipDefaultResolvers(true)
                .target(target)
                .load();
    }

    @BeforeAll
    static void setUp() throws SQLException {
        conn = DriverManager.getConnection(URL, "sa", "");
        // 分段 1：V1–V32（V33 还未 rename，users.phone 等明文列仍在，便于造数）
        flywayTo("32").migrate();
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("V33(__h2) 后：8 列已 rename 保留数据；cipher/last4/hmac 列在位；旧索引/约束已 DROP")
    void v33RenameKeepsData() throws SQLException {
        // users / sms_codes 各造一行（此时 phone 列还在），验证 rename 后数据保留
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO `users` (`id`, `phone`, `phone_hash`, `nickname`, `status`, `register_source`) "
                    + "VALUES (1, '13800001234', 'legacy-hash-1', 'smoke', 'ACTIVE', 'SELF')");
            st.execute("INSERT INTO `sms_codes` (`id`, `phone`, `scene`, `code`, `expire_at`) "
                    + "VALUES (1, '13800001234', 'LOGIN', '888888', DATEADD('DAY', 1, CURRENT_TIMESTAMP))");
        }

        // 分段 2：V33（rename 锚点；H2 变体：DROP CONSTRAINT + 8 RENAME）
        flywayTo("33").migrate();

        // rename 后数据保留
        assertEquals("13800001234", queryScalar("SELECT `phone__bak` FROM `users` WHERE id = 1"));
        assertEquals("legacy-hash-1", queryScalar("SELECT `phone_hash__bak` FROM `users` WHERE id = 1"));
        assertEquals("13800001234", queryScalar("SELECT `phone__bak` FROM `sms_codes` WHERE id = 1"));

        // V31 补列在位
        assertTrue(columnExists("users", "phone_cipher"));
        assertTrue(columnExists("users", "phone_last4"));
        assertTrue(columnExists("users", "phone_hmac"));
        assertTrue(columnExists("sms_codes", "phone_last4"));
        assertTrue(columnExists("sms_codes", "phone_hmac"));
        assertTrue(columnExists("tenants", "contact_phone_cipher"));
        assertTrue(columnExists("tenant_applications", "contact_phone_cipher"));
        assertTrue(columnExists("wholesaler_applications", "contact_phone_cipher"));
        assertTrue(columnExists("inquiry_requests", "rt_phone_cipher"));
        assertTrue(columnExists("inquiry_requests", "rt_phone_hmac"));
        assertTrue(columnExists("blacklist", "target_value_cipher"));
        assertTrue(columnExists("customer_prices", "rt_phone_last4"));
        assertTrue(columnExists("customer_prices", "rt_phone_hmac"));

        // 旧索引/约束已 DROP（H2 变体：DROP CONSTRAINT uk_phone_hash / uk_custprice_wh_phone_sku + DROP INDEX idx_custprice_phone）
        assertFalse(indexExists("users", "uk_phone_hash"));
        assertFalse(indexExists("customer_prices", "uk_custprice_wh_phone_sku"));
        assertFalse(indexExists("customer_prices", "idx_custprice_phone"));
        // 旧唯一键所在列已改名为 __bak（证明 DROP 先于 rename 执行成功）
        assertTrue(columnExists("users", "phone_hash__bak"));
    }

    @Test
    @Order(2)
    @DisplayName("V32 唯一索引升级在位（uk_phone_hmac / uk_blacklist_type_hmac / uk_custprice_wh_hmac_sku）")
    void v32UniqueIndexesInPlace() throws SQLException {
        assertTrue(indexExists("users", "uk_phone_hmac"));
        assertTrue(indexExists("blacklist", "uk_blacklist_type_hmac"));
        assertTrue(indexExists("customer_prices", "uk_custprice_wh_hmac_sku"));
    }

    @Test
    @Order(3)
    @DisplayName("V34(__h2) 后：8 个 __bak 列已 DROP；blacklist PHONE 行摘要改写正确（碰撞消歧 + LICENSE_NO 不动）")
    void v34DropAndBlacklistRewrite() throws SQLException {
        // 分段 3：seed blacklist（blacklist 列在 V34 不被 drop，此时仍是原表结构）
        try (Statement st = conn.createStatement()) {
            // PHONE：末 4 位互异的行（1234 / 5678 各唯一）
            st.execute("INSERT INTO `blacklist` (`id`, `target_type`, `target_value`, `target_value_hmac`, `reason`, `operator_user_id`, `status`) "
                    + "VALUES (1, 'PHONE', '13800001234', 'hmacAAAA', 'r1', 1, 'ACTIVE')");
            st.execute("INSERT INTO `blacklist` (`id`, `target_type`, `target_value`, `target_value_hmac`, `reason`, `operator_user_id`, `status`) "
                    + "VALUES (2, 'PHONE', '13800005678', 'hmacBBBB', 'r2', 1, 'ACTIVE')");
            // PHONE：末 4 位相同（9999）的碰撞对
            st.execute("INSERT INTO `blacklist` (`id`, `target_type`, `target_value`, `target_value_hmac`, `reason`, `operator_user_id`, `status`) "
                    + "VALUES (3, 'PHONE', '13911119999', 'hmacCCCC', 'r3', 1, 'ACTIVE')");
            st.execute("INSERT INTO `blacklist` (`id`, `target_type`, `target_value`, `target_value_hmac`, `reason`, `operator_user_id`, `status`) "
                    + "VALUES (4, 'PHONE', '13922229999', 'hmacDDDD', 'r4', 1, 'ACTIVE')");
            // LICENSE_NO：原文保留
            st.execute("INSERT INTO `blacklist` (`id`, `target_type`, `target_value`, `target_value_hmac`, `reason`, `operator_user_id`, `status`) "
                    + "VALUES (5, 'LICENSE_NO', 'SH-2026-0001', NULL, 'r5', 1, 'ACTIVE')");
        }

        // 分段 4：V34（DROP 8 个 __bak 列 + blacklist 摘要改写，H2 变体相关子查询写法）
        flywayTo("34").migrate();

        // 8 个 __bak 列已 DROP
        assertFalse(columnExists("users", "phone__bak"));
        assertFalse(columnExists("users", "phone_hash__bak"));
        assertFalse(columnExists("sms_codes", "phone__bak"));
        assertFalse(columnExists("tenants", "contact_phone__bak"));
        assertFalse(columnExists("tenant_applications", "contact_phone__bak"));
        assertFalse(columnExists("wholesaler_applications", "contact_phone__bak"));
        assertFalse(columnExists("inquiry_requests", "rt_phone__bak"));
        assertFalse(columnExists("customer_prices", "rt_phone__bak"));

        // 明文列彻底消失、cipher/hmac/last4 仍在
        assertFalse(columnExists("users", "phone"));
        assertFalse(columnExists("users", "phone_hash"));
        assertTrue(columnExists("users", "phone_cipher"));
        assertTrue(columnExists("users", "phone_hmac"));
        assertTrue(columnExists("users", "phone_last4"));
        assertTrue(columnExists("blacklist", "target_value_cipher"));

        // blacklist 摘要改写断言（H2 变体逐值 = MySQL 变体期望值，16 §1.5）
        assertEquals("PHONE_****1234", queryScalar("SELECT `target_value` FROM `blacklist` WHERE id = 1"));
        assertEquals("PHONE_****5678", queryScalar("SELECT `target_value` FROM `blacklist` WHERE id = 2"));
        // 碰撞对：追加 hmac 尾 4 消歧
        assertEquals("PHONE_****9999:CCCC", queryScalar("SELECT `target_value` FROM `blacklist` WHERE id = 3"));
        assertEquals("PHONE_****9999:DDDD", queryScalar("SELECT `target_value` FROM `blacklist` WHERE id = 4"));
        // LICENSE_NO 原文保留
        assertEquals("SH-2026-0001", queryScalar("SELECT `target_value` FROM `blacklist` WHERE id = 5"));

        // 摘要改写后唯一键仍成立（无重复）
        assertEquals(5, queryInt("SELECT COUNT(*) FROM `blacklist`"));
        assertEquals(5, queryInt("SELECT COUNT(*) FROM (SELECT DISTINCT `target_type`, `target_value` FROM `blacklist`) t"));
    }

    @Test
    @Order(4)
    @DisplayName("repair 重跑 V34 8.1/8.2 → blacklist 零变化（幂等守卫生效）")
    void repairRerunIdempotent() throws SQLException {
        List<String> before = blacklistSnapshot();

        // 与 V34__h2.sql 8.1/8.2 逐字一致的语句（含幂等守卫）
        String update81 = "UPDATE `blacklist` SET `target_value` = CONCAT('PHONE_****', RIGHT(`target_value`, 4)) "
                + "WHERE `target_type` = 'PHONE' AND `target_value` NOT LIKE 'PHONE_****%' "
                + "AND (SELECT COUNT(*) FROM `blacklist` b2 WHERE b2.`target_type` = 'PHONE' "
                + "AND RIGHT(b2.`target_value`, 4) = RIGHT(`blacklist`.`target_value`, 4)) = 1";
        String update82 = "UPDATE `blacklist` SET `target_value` = CONCAT('PHONE_****', RIGHT(`target_value`, 4), ':', RIGHT(`target_value_hmac`, 4)) "
                + "WHERE `target_type` = 'PHONE' AND `target_value` NOT LIKE 'PHONE_****%' "
                + "AND (SELECT COUNT(*) FROM `blacklist` b2 WHERE b2.`target_type` = 'PHONE' "
                + "AND RIGHT(b2.`target_value`, 4) = RIGHT(`blacklist`.`target_value`, 4)) > 1";

        try (Statement st = conn.createStatement()) {
            st.executeUpdate(update81);
            st.executeUpdate(update82);
        }
        List<String> after = blacklistSnapshot();
        assertEquals(before, after, "repair 重跑后 blacklist 值发生变化（幂等守卫失效）");
    }

    private static List<String> blacklistSnapshot() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT `target_type`, `target_value` FROM `blacklist` ORDER BY `id`")) {
            while (rs.next()) {
                out.add(rs.getString(1) + "|" + rs.getString(2));
            }
        }
        return out;
    }

    @Test
    @Order(5)
    @DisplayName("MySQL/H2 两变体声明同一变更集（同终态）——V33:3 索引 DROP+8 RENAME；V34:8 DROP+2 UPDATE(含幂等守卫)")
    void bothVariantsDeclareSameChanges() throws IOException {
        // 共 8 个 rename/drop 列、3 个旧索引、两条 blacklist UPDATE（两变体数量一致 = 同一终态）
        String mysqlV33 = readClasspath("db/migration/V33__pii_shrink_rename__mysql.sql");
        String h2V33 = readClasspath("db/migration/V33__pii_shrink_rename__h2.sql");
        String mysqlV34 = readClasspath("db/migration/V34__pii_shrink_drop__mysql.sql");
        String h2V34 = readClasspath("db/migration/V34__pii_shrink_drop__h2.sql");

        assertEquals(8, count(mysqlV33, "RENAME COLUMN `"), "MySQL V33 RENAME COLUMN 数不符");
        assertEquals(8, count(h2V33, "RENAME COLUMN `"), "H2 V33 RENAME COLUMN 数不符");
        assertEquals(3, count(mysqlV33, "DROP INDEX `"), "MySQL V33 旧索引 DROP 数不符（方言: DROP INDEX）");
        assertEquals(2, count(h2V33, "DROP CONSTRAINT IF EXISTS `"), "H2 V33 旧唯一约束 DROP 数不符（方言: DROP CONSTRAINT）");
        assertEquals(1, count(h2V33, "DROP INDEX IF EXISTS `"), "H2 V33 普通索引 DROP 数不符");

        assertEquals(8, count(mysqlV34, "DROP COLUMN `"), "MySQL V34 DROP COLUMN 数不符");
        assertEquals(8, count(h2V34, "DROP COLUMN `"), "H2 V34 DROP COLUMN 数不符");
        assertEquals(2, count(mysqlV34, "UPDATE `blacklist`"), "MySQL V34 blacklist UPDATE 数不符");
        assertEquals(2, count(h2V34, "UPDATE `blacklist`"), "H2 V34 blacklist UPDATE 数不符");

        // 幂等守卫必须存在于 V34 两条 UPDATE 中（两变体都要）
        assertTrue(mysqlV34.contains("NOT LIKE 'PHONE_****%'"), "MySQL V34 缺少幂等守卫");
        assertTrue(h2V34.contains("NOT LIKE 'PHONE_****%'"), "H2 V34 缺少幂等守卫");
    }

    private static String readClasspath(String resource) throws IOException {
        try (InputStream in = V33V34H2MigrationSmokeTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("classpath 资源不存在: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String text, String token) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(token, i)) >= 0) {
            n++;
            i += token.length();
        }
        return n;
    }

    private static String queryScalar(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "期望有结果: " + sql);
            return rs.getString(1);
        }
    }

    private static int queryInt(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static boolean columnExists(String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table.toUpperCase());
            ps.setString(2, column.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static boolean indexExists(String table, String index) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = ? AND INDEX_NAME = ?")) {
            ps.setString(1, table.toUpperCase());
            ps.setString(2, index.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
