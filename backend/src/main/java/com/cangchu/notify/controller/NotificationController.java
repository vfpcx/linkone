package com.cangchu.notify.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.notify.vo.NotificationVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站内信 Controller（P3 BE-W1，12 §4.3 三端点）。
 * 路径 /api/v1/notifications 已加入 SaTokenConfig checkLogin 段（登录用户，收件人=本人）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 我的消息列表（unreadOnly=true 只看未读）。
     *
     * @param group 分组筛选（P5-A W3，18-p5-design §4.4）：BIZ/ANNOUNCE/SYS，空=全部
     */
    @GetMapping
    public R<Page<NotificationVo>> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(defaultValue = "false") boolean unreadOnly,
                                        @RequestParam(required = false) String group) {
        return R.ok(notificationService.listMine(StpUtil.getLoginIdAsLong(), page, size, unreadOnly, group));
    }

    /** 全部已读（P5-A W3，本人 scope，幂等）。 */
    @PostMapping("/read-all")
    public R<Void> readAll() {
        notificationService.readAll(StpUtil.getLoginIdAsLong());
        return R.ok();
    }

    /** 我的未读数（铃铛角标轮询）。 */
    @GetMapping("/unread-count")
    public R<Map<String, Long>> unreadCount() {
        return R.ok(Map.of("count", notificationService.unreadCount(StpUtil.getLoginIdAsLong())));
    }

    /** 标记已读（本人校验，幂等）。 */
    @PostMapping("/{id}/read")
    public R<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id, StpUtil.getLoginIdAsLong());
        return R.ok();
    }
}
