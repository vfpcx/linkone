package com.cangchu.product.spec;

import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.product.dto.SpecDimensionDto;
import com.cangchu.product.vo.SpecDimensionVo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化规格模板支持（P6 商品规格模型，V42）。
 *
 * <p>职责：模板规范化与校验、JSON 序列化/反序列化、笛卡尔积展开、规格键与摘要推导。
 * 唯一事实源——{@code spus.spec_schema} 与 {@code skus.spec_key} 的读写口径全在此类，
 * service 层只做归属鉴权与落库。
 *
 * <p>上限（防组合爆炸）：维度 ≤ {@value #MAX_DIMENSIONS}、单维取值 ≤
 * {@value #MAX_OPTIONS_PER_DIMENSION}、组合总数 ≤ {@value #MAX_COMBINATIONS}；
 * 违规分别抛 50731（非法）/50732（超限）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpuSpecSchemaSupport {

    public static final int MAX_DIMENSIONS = 5;
    public static final int MAX_OPTIONS_PER_DIMENSION = 20;
    public static final int MAX_COMBINATIONS = 60;
    public static final int MAX_DIMENSION_NAME_LEN = 16;
    public static final int MAX_OPTION_LEN = 24;

    private static final TypeReference<List<SpecDimensionDto>> SCHEMA_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /** 规范化（去空白）+ 校验模板；返回新对象（不改入参）。空模板 → 50730。 */
    public List<SpecDimensionDto> normalize(List<SpecDimensionDto> schema) {
        if (schema == null || schema.isEmpty()) {
            throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_REQUIRED);
        }
        if (schema.size() > MAX_DIMENSIONS) {
            throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_LIMIT);
        }
        List<SpecDimensionDto> out = new ArrayList<>(schema.size());
        Set<String> dimNames = new LinkedHashSet<>();
        for (SpecDimensionDto dim : schema) {
            if (dim == null || dim.getName() == null || dim.getName().isBlank()) {
                throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_INVALID);
            }
            String name = dim.getName().trim();
            if (name.length() > MAX_DIMENSION_NAME_LEN || !dimNames.add(name)) {
                throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_INVALID, "规格维度名重复或超长：" + name);
            }
            List<String> options = dim.getOptions();
            if (options == null || options.isEmpty()) {
                throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_INVALID, "维度「" + name + "」缺少取值");
            }
            if (options.size() > MAX_OPTIONS_PER_DIMENSION) {
                throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_LIMIT);
            }
            List<String> normOptions = new ArrayList<>(options.size());
            Set<String> seen = new LinkedHashSet<>();
            for (String option : options) {
                if (option == null || option.isBlank()) {
                    throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_INVALID);
                }
                String v = option.trim();
                if (v.length() > MAX_OPTION_LEN || !seen.add(v)) {
                    throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_INVALID,
                            "维度「" + name + "」取值重复或超长：" + v);
                }
                normOptions.add(v);
            }
            SpecDimensionDto normalized = new SpecDimensionDto();
            normalized.setName(name);
            normalized.setOptions(normOptions);
            out.add(normalized);
        }
        if (combinationCount(out) > MAX_COMBINATIONS) {
            throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_LIMIT);
        }
        return out;
    }

    /** 组合总数（各维度取值数之积；空模板视为 0）。 */
    public int combinationCount(List<SpecDimensionDto> schema) {
        if (schema == null || schema.isEmpty()) {
            return 0;
        }
        long total = 1L;
        for (SpecDimensionDto dim : schema) {
            if (dim == null || dim.getOptions() == null || dim.getOptions().isEmpty()) {
                return 0;
            }
            total *= dim.getOptions().size();
            if (total > MAX_COMBINATIONS) {
                return MAX_COMBINATIONS + 1;
            }
        }
        return (int) total;
    }

    /** 模板 → JSON（存 spus.spec_schema）。 */
    public String writeSchema(List<SpecDimensionDto> schema) {
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            // 结构简单，理论不可达；防御性兜底避免脏写
            log.warn("[P6] 规格模板序列化失败", e);
            throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_INVALID);
        }
    }

    /** JSON → DTO 列表（读 spus.spec_schema 供内部使用）；空/脏数据返回空列表（不抛出）。 */
    public List<SpecDimensionDto> readSchema(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<SpecDimensionDto> parsed = objectMapper.readValue(json, SCHEMA_TYPE);
            return parsed != null ? parsed : List.of();
        } catch (Exception e) {
            log.warn("[P6] 规格模板反序列化失败，降级为空模板：{}", json, e);
            return List.of();
        }
    }

    /** DTO → VO（对外输出，避免 dto 包外泄）。 */
    public List<SpecDimensionVo> toVo(List<SpecDimensionDto> schema) {
        if (schema == null) {
            return List.of();
        }
        List<SpecDimensionVo> out = new ArrayList<>(schema.size());
        for (SpecDimensionDto dim : schema) {
            out.add(new SpecDimensionVo(dim.getName(),
                    dim.getOptions() != null ? List.copyOf(dim.getOptions()) : List.of()));
        }
        return out;
    }

    /** 完整笛卡尔积展开（保序；每组合为「维度名 → 取值」有序映射）。 */
    public List<LinkedHashMap<String, String>> expand(List<SpecDimensionDto> schema) {
        List<LinkedHashMap<String, String>> combos = new ArrayList<>();
        combos.add(new LinkedHashMap<>());
        for (SpecDimensionDto dim : schema) {
            List<LinkedHashMap<String, String>> next = new ArrayList<>(combos.size() * dim.getOptions().size());
            for (LinkedHashMap<String, String> base : combos) {
                for (String option : dim.getOptions()) {
                    LinkedHashMap<String, String> row = new LinkedHashMap<>(base);
                    row.put(dim.getName(), option);
                    next.add(row);
                }
            }
            combos = next;
        }
        return combos;
    }

    /**
     * 由「维度名 → 取值」映射构造有序组合，并校验与模板匹配（维度齐全、无多余、取值在模板内）；
     * 不匹配 → 50734。
     */
    public LinkedHashMap<String, String> matchCombo(Map<String, String> options, List<SpecDimensionDto> schema) {
        if (options == null || options.size() != schema.size()) {
            throw new BizException(ErrorCode.SPU_SPEC_COMBO_INVALID);
        }
        LinkedHashMap<String, String> combo = new LinkedHashMap<>();
        for (SpecDimensionDto dim : schema) {
            String value = options.get(dim.getName());
            if (value == null || !dim.getOptions().contains(value)) {
                throw new BizException(ErrorCode.SPU_SPEC_COMBO_INVALID,
                        "维度「" + dim.getName() + "」取值不在规格模板内");
            }
            combo.put(dim.getName(), value);
        }
        return combo;
    }

    /** 规格键：紧凑有序 JSON，如 {"包装":"5L/桶","口味":"原味"}。 */
    public String toSpecKey(LinkedHashMap<String, String> combo) {
        try {
            return objectMapper.writeValueAsString(combo);
        } catch (Exception e) {
            log.warn("[P6] 规格键序列化失败", e);
            throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_INVALID);
        }
    }

    /** 规格摘要（SKU name/spec 展示用）：取值按模板顺序以 " / " 连接。 */
    public String summary(LinkedHashMap<String, String> combo) {
        return String.join(" / ", combo.values());
    }
}
