package com.cangchu.common.pii;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PII 硬化配置（15-pii-hardening-v2 §3）。
 *
 * <p>命名空间 {@code cangchu.pii.*}（沿用 cangchu.sms.* 先例，字段多于 2 个故升格
 * {@code @ConfigurationProperties}）。密钥落点：
 * <ul>
 *   <li>prod：application-prod.yml 经 {@code ${PII_HMAC_KEY}} / {@code ${PII_HMAC_KAT}}
 *       注入，故意【无默认值】——缺失即启动失败（fail-fast，复刻 MYSQL_PASSWORD 先例）。</li>
 *   <li>dev/test：固定测试密钥写在 application-dev.yml / 测试 application.yml，仅供测试。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "cangchu.pii")
public class PiiProperties {

    /** HMAC-SHA256 盲索引密钥（Base64 编码 256-bit）。索引密钥不轮换（15 §3）。 */
    private String hmacKey;

    /**
     * 启动 KAT（known-answer test）期望值：{@code hex(HMAC(hmacKey, "13800138000"))}。
     * Spring 占位符只对「缺失」fail-fast，对「错值」不 fail——KAT 把配错密钥拦在启动期。
     * prod 期望值随部署脚本走，不进 git。
     */
    private String hmacKat;

    /**
     * 写模式：{@code legacy}（只写旧列）| {@code dual}（同写旧列 + hmac 列）。
     * 阶段 0 回滚口径：拨回 legacy 即止血（新列数据留存无害）。
     */
    private String writeMode = "legacy";

    /**
     * 是否在启动时跑一次存量回填（阶段 0 一次性动作）。默认 false——跑完请拨回，
     * 否则每次重启都空扫全表。见 {@link PiiBackfillRunner}。
     */
    private boolean backfillOnStartup = false;

    /** 存量回填单批行数（批内逐行 CAS 更新，不长事务持锁）。 */
    private int backfillBatchSize = 500;

    /**
     * 全局读模式（阶段 1 新增，15 §4 阶段1）：
     * <ul>
     *   <li>{@code plain}——只读旧列（phone_hash / 明文），阶段 0 口径；</li>
     *   <li>{@code shadow}——<b>仍以旧列出结果</b>，同时用 hmac 列再查一遍，仅比对+计数
     *       （Step 1 验证期，零行为变化，见 {@link PiiShadowReader}）；</li>
     *   <li>{@code hmac}——<b>切读</b>：hmac 列直接出结果，明文列不再参与判定。Step 2 是<b>硬切</b>
     *       ——hmac 未命中即真未命中，<b>没有旧列兜底</b>（回填是否填全，由切读前的影子期闸门证明，
     *       不靠运行时兜底掩盖；兜底自愈 + 异步补写是 Step 3 登录链另一套口径）。</li>
     * </ul>
     * 回滚口径：拨回 shadow/plain 即恢复旧列读路径，秒级、无数据损失。
     *
     * <p>本字段是<b>全局默认</b>，作用于登录链（A1–A6，归 Step 3）与所有未在
     * {@link #readModes} 里登记覆写的模块。
     */
    private String readMode = "plain";

    /**
     * 分模块读模式覆写（阶段 1 Step 2 新增，15 §4 Step 2）：key = 模块名（取值见 {@link PiiModule}），
     * value 与 {@link #readMode} 同三档。未登记的模块回落到全局 {@link #readMode}。
     *
     * <p>灰度与回滚都按模块走：切读时一块一块放，出事时只拨回出事的那块，已观察合格的其余模块
     * 不受牵连。模块名与模式取值在启动期校验，写错即拒绝启动（见 {@link PiiReadRouter}）。
     */
    private Map<String, String> readModes = new LinkedHashMap<>();

    /** 是否双写（阶段 0 唯一分叉点；读路径与本开关无关，一律走旧列）。 */
    public boolean isDualWrite() {
        return "dual".equalsIgnoreCase(writeMode);
    }

    /** 是否影子双查（阶段 1 Step 1；出结果的仍是旧列，本开关只决定要不要多查一次比对）。 */
    public boolean isShadowRead() {
        return "shadow".equalsIgnoreCase(readMode);
    }

    /** 某模块生效的读模式：模块覆写优先，未登记（或登记为空）回落全局 {@link #readMode}。 */
    public String readMode(String module) {
        String override = readModes.get(module);
        return (override == null || override.isBlank()) ? readMode : override;
    }

    /** 该模块是否处于影子双查（出结果的仍是旧列）。 */
    public boolean isShadowRead(String module) {
        return "shadow".equalsIgnoreCase(readMode(module));
    }

    /**
     * 该模块是否已<b>切读</b>（阶段 1 Step 2）：hmac 列直接出结果，明文列不再参与判定，
     * 且 hmac 未命中即真未命中——无旧列兜底。拨回 shadow/plain 即恢复旧列读路径。
     */
    public boolean isHmacRead(String module) {
        return "hmac".equalsIgnoreCase(readMode(module));
    }
}
