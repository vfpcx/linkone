package com.cangchu.notify.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 站内信出参（P3 BE-W1）。 */
@Data
@Builder
public class NotificationVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String type;

    private String title;

    private String content;

    private String refType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long refId;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
