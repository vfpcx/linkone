package com.cangchu.account;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.*;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号模块测试（8 个用例）：注册 / 登录（密码+验证码）/ 改密 / 找回密码 / RT免密 / 多角色 / 退出登录
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl;

    private static String token;
    private static Long userId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/v1/account";
    }

    // ==================== Case 1: 注册 ====================
    @Test
    @Order(1)
    @DisplayName("注册 - 密码注册")
    void testRegister() {
        RegisterDto dto = new RegisterDto();
        dto.setPhone("13800001001");
        dto.setPassword("Pass1234");
        dto.setSmsCode("888888");
        dto.setRole("TA");
        dto.setNickname("测试TA");

        ResponseEntity<R<LoginVo>> response = restTemplate.exchange(
                baseUrl + "/register",
                HttpMethod.POST,
                new HttpEntity<>(dto),
                new ParameterizedTypeReference<R<LoginVo>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        R<LoginVo> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().getToken()).isNotBlank();
        assertThat(body.getData().getPrimaryRole()).isEqualTo("TA");
        assertThat(body.getData().getRoleList()).isNotEmpty();

        token = body.getData().getToken();
        userId = body.getData().getUserId();
    }

    // ==================== Case 2: 注册重复手机号拒绝 ====================
    @Test
    @Order(2)
    @DisplayName("重复手机号注册应拒绝")
    void testDuplicateRegister() {
        RegisterDto dto = new RegisterDto();
        dto.setPhone("13800001001");
        dto.setPassword("Pass1234");
        dto.setSmsCode("888888");

        ResponseEntity<R<LoginVo>> response = restTemplate.exchange(
                baseUrl + "/register",
                HttpMethod.POST,
                new HttpEntity<>(dto),
                new ParameterizedTypeReference<R<LoginVo>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCode()).isNotEqualTo(0);
    }

    // ==================== Case 3: 登录（密码） ====================
    @Test
    @Order(3)
    @DisplayName("登录 - 密码方式")
    void testPasswordLogin() {
        LoginDto dto = new LoginDto();
        dto.setPhone("13800001001");
        dto.setPassword("Pass1234");
        dto.setDevice("PC");

        ResponseEntity<R<LoginVo>> response = restTemplate.exchange(
                baseUrl + "/login",
                HttpMethod.POST,
                new HttpEntity<>(dto),
                new ParameterizedTypeReference<R<LoginVo>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        R<LoginVo> body = response.getBody();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().getToken()).isNotBlank();
    }

    // ==================== Case 4: 错误密码拒绝 ====================
    @Test
    @Order(4)
    @DisplayName("错误密码登录应拒绝")
    void testWrongPasswordLogin() {
        LoginDto dto = new LoginDto();
        dto.setPhone("13800001001");
        dto.setPassword("WrongPass1");

        ResponseEntity<R<LoginVo>> response = restTemplate.exchange(
                baseUrl + "/login",
                HttpMethod.POST,
                new HttpEntity<>(dto),
                new ParameterizedTypeReference<R<LoginVo>>() {});

        R<LoginVo> body = response.getBody();
        assertThat(body.getCode()).isNotEqualTo(0);
    }

    // ==================== Case 5: 验证码登录 ====================
    @Test
    @Order(5)
    @DisplayName("登录 - 验证码方式")
    void testSmsCodeLogin() {
        // 先发送验证码
        SmsCodeSendDto smsDto = new SmsCodeSendDto();
        smsDto.setPhone("13800001001");
        smsDto.setScene("LOGIN");
        restTemplate.exchange(
                baseUrl + "/sms-code",
                HttpMethod.POST,
                new HttpEntity<>(smsDto),
                new ParameterizedTypeReference<R<Void>>() {});

        // 验证码登录
        LoginDto dto = new LoginDto();
        dto.setPhone("13800001001");
        dto.setSmsCode("888888");

        ResponseEntity<R<LoginVo>> response = restTemplate.exchange(
                baseUrl + "/login",
                HttpMethod.POST,
                new HttpEntity<>(dto),
                new ParameterizedTypeReference<R<LoginVo>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
        assertThat(response.getBody().getData().getToken()).isNotBlank();
    }

    // ==================== Case 6: 修改密码 ====================
    @Test
    @Order(6)
    @DisplayName("修改密码")
    void testChangePassword() {
        // 先登录获取 token
        LoginDto loginDto = new LoginDto();
        loginDto.setPhone("13800001001");
        loginDto.setPassword("Pass1234");
        ResponseEntity<R<LoginVo>> loginResp = restTemplate.exchange(
                baseUrl + "/login", HttpMethod.POST,
                new HttpEntity<>(loginDto),
                new ParameterizedTypeReference<R<LoginVo>>() {});
        String authToken = loginResp.getBody().getData().getToken();

        // 改密
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword("Pass1234");
        dto.setNewPassword("NewPass456");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authToken);
        ResponseEntity<R<Void>> response = restTemplate.exchange(
                baseUrl + "/password",
                HttpMethod.PUT,
                new HttpEntity<>(dto, headers),
                new ParameterizedTypeReference<R<Void>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
    }

    // ==================== Case 7: 找回密码 ====================
    @Test
    @Order(7)
    @DisplayName("找回密码")
    void testResetPassword() {
        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setPhone("13800001001");
        dto.setSmsCode("888888");
        dto.setNewPassword("ResetPwd789");

        ResponseEntity<R<Void>> response = restTemplate.exchange(
                baseUrl + "/password/reset",
                HttpMethod.POST,
                new HttpEntity<>(dto),
                new ParameterizedTypeReference<R<Void>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
    }

    // ==================== Case 8: RT 免密验证码登录（首次自动注册） ====================
    @Test
    @Order(8)
    @DisplayName("RT 免密验证码登录（首次自动注册）")
    void testRtSmsLogin() {
        ResponseEntity<R<LoginVo>> response = restTemplate.exchange(
                baseUrl + "/login/rt?phone=13900001001&code=888888",
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<R<LoginVo>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
        assertThat(response.getBody().getData().getPrimaryRole()).isEqualTo("RT");
        assertThat(response.getBody().getData().getIsNew()).isTrue();
    }

    // ==================== Case 9: 多角色登录 ====================
    @Test
    @Order(9)
    @DisplayName("多角色登录返回 roleList")
    void testMultiRoleLogin() {
        // 注册第二个角色（同手机号不同场景）
        RegisterDto dto = new RegisterDto();
        dto.setPhone("13800001002");
        dto.setPassword("Test5678");
        dto.setSmsCode("888888");
        dto.setRole("WA");
        dto.setNickname("测试WA");

        ResponseEntity<R<LoginVo>> registerResp = restTemplate.exchange(
                baseUrl + "/register", HttpMethod.POST,
                new HttpEntity<>(dto),
                new ParameterizedTypeReference<R<LoginVo>>() {});

        assertThat(registerResp.getBody().getCode()).isEqualTo(0);
        assertThat(registerResp.getBody().getData().getRoleList()).hasSize(1);

        LoginVo loginData = registerResp.getBody().getData();
        assertThat(loginData.getPrimaryRole()).isEqualTo("WA");
    }

    // ==================== Case 10: 退出登录 ====================
    @Test
    @Order(10)
    @DisplayName("退出登录")
    void testLogout() {
        // 先登录
        LoginDto loginDto = new LoginDto();
        loginDto.setPhone("13800001002");
        loginDto.setPassword("Test5678");
        ResponseEntity<R<LoginVo>> loginResp = restTemplate.exchange(
                baseUrl + "/login", HttpMethod.POST,
                new HttpEntity<>(loginDto),
                new ParameterizedTypeReference<R<LoginVo>>() {});
        String authToken = loginResp.getBody().getData().getToken();

        // 退出
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authToken);
        ResponseEntity<R<Void>> response = restTemplate.exchange(
                baseUrl + "/logout", HttpMethod.POST,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<R<Void>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
    }
}
