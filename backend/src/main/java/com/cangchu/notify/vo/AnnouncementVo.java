package com.cangchu.notify.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 平台公告出参（P5-A W3，18-p5-design §4.2）。 */
@Data
@Builder
public class AnnouncementVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String title;

    private String content;

    /** 角色组 KEY 列表（出参展开为数组，便于前端渲染） */
    private List<String> targetRoles;

    /** DRAFT / PUBLISHED / INACTIVE */
    private String status;

    private LocalDateTime publishedAt;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long publishedBy;

    private LocalDateTime createdAt;
}
