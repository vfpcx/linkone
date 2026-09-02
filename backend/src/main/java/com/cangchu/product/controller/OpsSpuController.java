package com.cangchu.product.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
import com.cangchu.product.dto.SpuCreateDto;
import com.cangchu.product.service.SpuService;
import com.cangchu.product.vo.SpuCategoryGroupVo;
import com.cangchu.product.vo.SpuVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OPS 平台标品库（P5-D D56，22 §3.1；归属 product 域，平台级）。
 * 鉴权 requireOps 在 SpuService 内完成（hasRole 校验，不信任客户端，公告/黑名单先例）。
 */
@RestController
@RequestMapping("/api/v1/ops/spus")
@RequiredArgsConstructor
public class OpsSpuController {

    private final SpuService spuService;

    /** 标品分页列表（keyword 名称/编码模糊 + 品类/状态过滤）。 */
    @GetMapping
    public R<Page<SpuVo>> page(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String categoryL1,
                               @RequestParam(required = false) String categoryL2,
                               @RequestParam(required = false) String status) {
        return R.ok(spuService.page(StpUtil.getLoginIdAsLong(), page, size, keyword, categoryL1, categoryL2, status));
    }

    /** 新增标品（ACTIVE；编码可填可自动）。 */
    @PostMapping
    public R<SpuVo> create(@Valid @RequestBody SpuCreateDto dto) {
        return R.ok(spuService.create(StpUtil.getLoginIdAsLong(), dto));
    }

    /** 下架：ACTIVE → OFFLINE（存量 SKU 引用保留，仅禁新挂接）。 */
    @PostMapping("/{id}/offline")
    public R<Void> offline(@PathVariable Long id) {
        spuService.offline(StpUtil.getLoginIdAsLong(), id);
        return R.ok();
    }

    /** 合并 source → target（同事务：引用 SKU 原子重指 + 快照刷新）。 */
    @PostMapping("/{id}/merge")
    public R<Void> merge(@PathVariable Long id, @RequestParam Long targetSpuId) {
        spuService.merge(StpUtil.getLoginIdAsLong(), id, targetSpuId);
        return R.ok();
    }

    /** 两级品类字典（OPS 新增弹窗下拉同源；字典维护界面后置）。 */
    @GetMapping("/spu-categories")
    public R<List<SpuCategoryGroupVo>> categories() {
        return R.ok(spuService.categories(StpUtil.getLoginIdAsLong()));
    }
}
