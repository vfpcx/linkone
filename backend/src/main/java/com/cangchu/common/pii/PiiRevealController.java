package com.cangchu.common.pii;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PII 阶段 2 · 查全号端点（15-pii-hardening-v2 §4 阶段2-1 / 波次 PII-W7）。
 *
 * <p>{@code GET /api/v1/pii/phone-reveal?biz=BLACKLIST&id=1}——VO 层默认只回打码号，
 * 业务确需完整手机号的场景（OPS 审核、TA 审批联系、WA 联系买家）经此端点取号。
 * 权限 + 归属校验 + 审计在 {@link PiiRevealService} 内完成；已被 SaInterceptor 登录拦截覆盖。
 */
@RestController
@RequestMapping("/api/v1/pii/phone-reveal")
@RequiredArgsConstructor
public class PiiRevealController {

    private final PiiRevealService piiRevealService;

    @GetMapping
    public R<Map<String, String>> reveal(@RequestParam String biz, @RequestParam Long id) {
        String phone = piiRevealService.reveal(StpUtil.getLoginIdAsLong(), biz, id);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("phone", phone);
        return R.ok(data);
    }
}
