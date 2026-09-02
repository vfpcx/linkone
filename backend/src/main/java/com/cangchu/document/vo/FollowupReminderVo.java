package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/** 跟进提醒出参（C3 · 24-p5-c-c3 §4.1/§4.4，document 域）。 */
@Data
public class FollowupReminderVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String content;

    private LocalDateTime remindAt;

    /** 触发时刻（空=未触发/待提醒） */
    private LocalDateTime remindedAt;

    private LocalDateTime createdAt;
}
