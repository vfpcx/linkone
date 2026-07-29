package com.cangchu.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 缺陷修复回归（FE-W1 契约偏差②）：/files/** 静态映射启动序缺陷。
 *
 * <p>旧实现：启动时 upload-dir 尚不存在 → {@code Path.toUri()} 生成的 URI 缺尾斜杠
 * （file:/x/uploads 而非 file:/x/uploads/）→ 映射按"文件"注册 → 上传成功但 GET 500，
 * 重启后自愈。修复：注册前 createDirectories + 手工保证尾斜杠（双保险）。
 *
 * <p>纯单元测试（不起 Spring 容器）：直击 {@link SaTokenConfig#resolveUploadLocation}。
 */
class UploadDirLocationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("P3-FILE-01 目录不存在：注册前自动创建，URI 以 / 结尾（目录语义）")
    void createsMissingDirAndAppendsTrailingSlash() {
        Path missing = tempDir.resolve("not-yet/uploads");
        assertThat(Files.exists(missing)).isFalse();
        // 缺陷复现前提：不存在的路径 toUri() 就是缺尾斜杠的（旧实现的 500 根因）
        assertThat(missing.toUri().toString()).doesNotEndWith("/");

        String location = SaTokenConfig.resolveUploadLocation(missing.toString());

        assertThat(location).startsWith("file:").endsWith("/");
        assertThat(Files.isDirectory(missing)).as("启动时即创建目录，不再依赖首次上传").isTrue();
        assertThat(location).isEqualTo(missing.toUri().toString()); // 目录存在后 toUri 带尾斜杠，两者一致
    }

    @Test
    @DisplayName("P3-FILE-02 目录已存在：幂等，URI 仍以 / 结尾且指向同一目录")
    void idempotentWhenDirExists() throws Exception {
        Path existing = tempDir.resolve("uploads");
        Files.createDirectories(existing);

        String first = SaTokenConfig.resolveUploadLocation(existing.toString());
        String second = SaTokenConfig.resolveUploadLocation(existing.toString());

        assertThat(first).endsWith("/").isEqualTo(second).isEqualTo(existing.toUri().toString());
    }

    @Test
    @DisplayName("P3-FILE-03 相对路径（生产默认 ./data/uploads 形态）：归一化为绝对目录 URI")
    void normalizesRelativePath() {
        String location = SaTokenConfig.resolveUploadLocation("./target/test-uploads-reg/./sub");

        assertThat(location).startsWith("file:").endsWith("/").doesNotContain("/./");
        assertThat(Files.isDirectory(Path.of("./target/test-uploads-reg/sub"))).isTrue();
    }
}
