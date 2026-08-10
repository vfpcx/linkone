package com.cangchu.account;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X硬化 H3（11-hardening-design §3）：Sa-Token active-timeout 闲置冻结场景测试。
 *
 * <p>主配已改为 active-timeout: 1800（prod 安全默认），dev/test 覆盖回 -1。
 * 本类用 properties 覆盖为 3 秒专测冻结语义（独立 Spring 上下文，不影响其余套件）：
 * <ul>
 *   <li>HD-H3-01：闲置超过 active-timeout 后请求 → TOKEN_FREEZE → 统一 401/41001（前端无需新分支）。</li>
 *   <li>HD-H3-02：持续活跃（每次请求自动续活）不掉线——总时长超过 active-timeout 但单段闲置未超。</li>
 * </ul>
 *
 * <p>探针接口用 GET /api/v1/notifications/unread-count（登录即可、无副作用、不消耗会话）。
 *
 * <p><b>SaManager 静态单例泄漏防护（P4-W5a 移交项，测试侧修复）</b>：本类的第二 Spring 上下文
 * 启动时经 sa-token starter 注入 {@code SaManager.setConfig(...)}，把 active-timeout=3 写进
 * <i>JVM 全局静态单例</i>——同 JVM 内后续所有测试类（复用缓存主上下文）都会被冻结语义污染
 * （长静默用例撞 41001，见 BillExportScenarioTest 5000 行压测注释）。@DirtiesContext 只关上下文
 * 不还原静态字段、独立 fork 需动 surefire 全局配置，均非最简。此处取「捕获-还原」：
 * {@code @BeforeAll}（先于本类上下文加载执行）直读 {@code SaManager.config} 公共字段捕获
 * 主上下文配置（避免 getConfig() 触发惰性默认初始化），{@code @AfterAll} 原样还原。
 */
@SpringBootTest(classes = CangchuApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sa-token.active-timeout=3")
class SessionActiveTimeoutTest {

    /** 本类上下文加载前的 JVM 全局 sa-token 配置（可能为 null=本类为首个加载的上下文） */
    private static SaTokenConfig globalConfigBeforeClass;

    @BeforeAll
    static void captureGlobalSaTokenConfig() {
        // 直读公共静态字段而非 getConfig()：后者在 null 时会惰性初始化默认配置，破坏还原语义
        globalConfigBeforeClass = SaManager.config;
    }

    @AfterAll
    static void restoreGlobalSaTokenConfig() {
        // 直写字段还原（对称于捕获）：不走 setConfig()，避免 null 分支副作用与事件回调
        SaManager.config = globalConfigBeforeClass;
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // 1[3-9] + 5位时间戳尾段 + 4位自增 = 11 位，与既有套件同风格且前缀不同（14 段），避免撞号
    private static final String PHONE_PREFIX =
            "14" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Long>>> UNREAD =
            new ParameterizedTypeReference<>() {};

    private String uniquePhone() {
        return PHONE_PREFIX + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private String registerAndGetToken() {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(uniquePhone());
        dto.setPassword("Pass1234");
        dto.setSmsCode("888888");
        dto.setRole("TA");
        dto.setNickname("H3闲置冻结");
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/account/register",
                HttpMethod.POST, new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        return body.getData().getToken();
    }

    private ResponseEntity<R<Map<String, Long>>> probe(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/notifications/unread-count",
                HttpMethod.GET, new HttpEntity<>(h), UNREAD);
    }

    @Test
    @DisplayName("HD-H3-01 闲置超过 active-timeout(3s) → TOKEN_FREEZE 统一映射 401/41001")
    void hdH3_01_idleFreeze() throws InterruptedException {
        String token = registerAndGetToken();

        // 登录后立即访问：正常
        ResponseEntity<R<Map<String, Long>>> ok = probe(token);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isNotNull();
        assertThat(ok.getBody().getCode()).isEqualTo(0);

        // 闲置 4.5s（> 3s）：冻结，走既有 NotLoginException 全局处理 → 401 + 41001
        Thread.sleep(4500);
        ResponseEntity<R<Map<String, Long>>> frozen = probe(token);
        assertThat(frozen.getStatusCode())
                .as("闲置冻结应与其余未登录态一致返回 HTTP 401（前端已有统一跳登录分支）")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(frozen.getBody()).isNotNull();
        assertThat(frozen.getBody().getCode()).isEqualTo(41001);
    }

    @Test
    @DisplayName("HD-H3-02 持续活跃自动续活：总时长超 active-timeout 但不掉线")
    void hdH3_02_activityRenews() throws InterruptedException {
        String token = registerAndGetToken();

        // 4 段 × 1s 闲置 = 总时长 4s > 3s，但每段闲置 < 3s，应持续续活不冻结
        for (int i = 0; i < 4; i++) {
            Thread.sleep(1000);
            ResponseEntity<R<Map<String, Long>>> resp = probe(token);
            assertThat(resp.getStatusCode())
                    .as("第 %d 次活跃探针不应被冻结（每次请求应续活 last-active）", i + 1)
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getCode()).isEqualTo(0);
        }
    }
}
