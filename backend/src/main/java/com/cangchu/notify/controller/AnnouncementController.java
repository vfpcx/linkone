package com.cangchu.notify.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
import com.cangchu.notify.dto.AnnouncementCreateDto;
import com.cangchu.notify.service.AnnouncementService;
import com.cangchu.notify.vo.AnnouncementVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台公告管理（OPS，P5-A W3，18-p5-design §4.2）。
 * Service 层 hasRole("OPS") 校验（不信任客户端，Blacklist 先例）。
 */
@RestController
@RequestMapping("/api/v1/ops/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /** 创建公告草稿（DRAFT） */
    @PostMapping
    public R<Long> create(@Valid @RequestBody AnnouncementCreateDto dto) {
        return R.ok(announcementService.create(StpUtil.getLoginIdAsLong(), dto));
    }

    /** 公告列表（可按 status 过滤） */
    @GetMapping
    public R<Page<AnnouncementVo>> page(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) String status) {
        return R.ok(announcementService.page(StpUtil.getLoginIdAsLong(), page, size, status));
    }

    /** 公告详情 */
    @GetMapping("/{id}")
    public R<AnnouncementVo> detail(@PathVariable Long id) {
        return R.ok(announcementService.detail(StpUtil.getLoginIdAsLong(), id));
    }

    /** 发布：DRAFT→PUBLISHED + 同事务批量写目标角色站内信 */
    @PostMapping("/{id}/publish")
    public R<Void> publish(@PathVariable Long id) {
        announcementService.publish(StpUtil.getLoginIdAsLong(), id);
        return R.ok();
    }

    /** 下架：PUBLISHED→INACTIVE（已发站内信保留） */
    @PostMapping("/{id}/inactivate")
    public R<Void> inactivate(@PathVariable Long id) {
        announcementService.inactivate(StpUtil.getLoginIdAsLong(), id);
        return R.ok();
    }
}
