package com.cangchu.notify.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 平台公告创建请求（P5-A W3，18-p5-design §4.2）。 */
@Data
public class AnnouncementCreateDto {

    @NotBlank(message = "公告标题不能为空")
    @Size(max = 128, message = "公告标题不能超过128字")
    private String title;

    @NotBlank(message = "公告正文不能为空")
    @Size(max = 512, message = "公告正文不能超过512字")
    private String content;

    /** 角色组 KEY（ALL/OPS/TA/WK_ST/WA_WE），至少一个 */
    @NotEmpty(message = "目标角色组不能为空")
    private List<String> targetRoles;
}
