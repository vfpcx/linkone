package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

/**
 * 公开租户目录项（P2 Wave6 DEF-1，供 WA 注册页选择目标仓库）。
 *
 * <p>防枚举收敛：仅 id + name 两个字段——严禁携带 contactPhone/licenseNo 等敏感字段
 * （匿名可访问端点，G-8.2）。id 序列化为字符串（前端 Long 精度惯例）。
 */
@Data
@Builder
public class TenantDirectoryItemVo {

    /** 租户（仓库）id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 仓库名 */
    private String name;
}
