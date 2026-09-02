package com.cangchu.inventory.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 批次移库入参（P5-D C2，25-p5-c-c2 §4.4：PUT /api/v1/tenant/batches/{id}/location）。
 * location 可 null=清空货位（to_location 记 NULL）；新旧相同=幂等空转不落日志。
 */
@Data
public class BatchLocationUpdateDto {

    /** 新货位号（自由文本 ≤64；null=清空） */
    @Size(max = 64, message = "货位号最长 64 字")
    private String location;
}
