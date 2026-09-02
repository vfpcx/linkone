package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新建客户跟进提醒（C3 · 24-p5-c-c3 §4.4）：content ≤200 必填；remindAt 须晚于 now（否则 50841）。
 */
@Data
public class CustomerReminderDto {

    @NotNull(message = "wholesalerId 不能为空")
    private Long wholesalerId;

    @NotBlank(message = "提醒内容不能为空")
    @Size(max = 200, message = "提醒内容不能超过 200 字")
    private String content;

    @NotNull(message = "remindAt 不能为空")
    private LocalDateTime remindAt;
}
