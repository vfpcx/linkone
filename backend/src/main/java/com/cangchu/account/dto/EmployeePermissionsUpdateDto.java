package com.cangchu.account.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * WA 调整员工授权位入参（P2 入驻 Wave3）。整体替换语义；空数组=收回全部授权（只读）。
 */
@Data
public class EmployeePermissionsUpdateDto {

    /** 授权位全集替换，白名单 PRICE_EDIT / INQUIRY_CONFIRM（服务层校验 50319） */
    @NotNull(message = "permissions 不能为空（收回全部授权请传空数组）")
    private List<String> permissions;
}
