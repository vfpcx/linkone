package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.BlacklistAddDto;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.service.BlacklistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OPS 平台黑名单 Controller（P2 Wave1）。
 * 路径前缀 /api/v1/ops/blacklist，已被 SaInterceptor 登录拦截覆盖；
 * OPS 角色校验在服务层显式 hasRole（现有模式），不信任客户端。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/blacklist")
public class OpsBlacklistController {

    private final BlacklistService blacklistService;

    /** 黑名单列表（可按 status 过滤：ACTIVE/REMOVED） */
    @GetMapping
    public R<List<Blacklist>> list(@RequestParam(required = false) String status) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(blacklistService.list(userId, status));
    }

    /** 加入黑名单（手机号/执照号双键） */
    @PostMapping
    public R<Blacklist> add(@Valid @RequestBody BlacklistAddDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(blacklistService.add(userId, dto));
    }

    /** 解除黑名单（保留追溯：status=REMOVED + removed_at） */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        blacklistService.remove(userId, id);
        return R.ok();
    }
}
