package com.cangchu.product.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import com.cangchu.product.dto.MerchantSpuCreateDto;
import com.cangchu.product.dto.MerchantSpuUpdateDto;
import com.cangchu.product.dto.SkuGenerateDto;
import com.cangchu.product.service.MerchantSpuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.product.vo.SpuVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商户自建聚合 SPU（P6 商品规格模型，V42；归属 product 域）。
 *
 * <p>路径前缀 /api/v1/tenant/spus，已被 SaInterceptor 登录拦截覆盖（G-1.1）。
 * 鉴权（该商户 WA / 该租户 TA，只读放宽 WK）与跨租户隔离均在 MerchantSpuService 内以
 * 登录态 + user_roles 推导，不信任客户端传参（SkuController 先例）。
 *
 * <p>与平台标品（/api/v1/ops/spus、/api/v1/catalog/spus）互不影响：本组端点只操作
 * {@code owner_type='TENANT'} 行。
 */
@RestController
@RequestMapping("/api/v1/tenant/spus")
@RequiredArgsConstructor
public class MerchantSpuController {

    private final MerchantSpuService merchantSpuService;

    /** 创建自建聚合 SPU（含规格模板；编码自动 TSPU-xxx）。 */
    @PostMapping
    public R<SpuVo> create(@RequestParam Long wholesalerId, @Valid @RequestBody MerchantSpuCreateDto dto) {
        return R.ok(merchantSpuService.create(wholesalerId, dto, StpUtil.getLoginIdAsLong()));
    }

    /** 更新（partial；规格模板非空时整体替换）。 */
    @PutMapping("/{id}")
    public R<SpuVo> update(@PathVariable Long id, @Valid @RequestBody MerchantSpuUpdateDto dto) {
        return R.ok(merchantSpuService.update(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /** 某商户的自建聚合 SPU 列表（含引用 SKU 数）。 */
    @GetMapping
    public R<List<SpuVo>> list(@RequestParam Long wholesalerId) {
        return R.ok(merchantSpuService.list(wholesalerId, StpUtil.getLoginIdAsLong()));
    }

    /** 详情（含规格模板与引用 SKU 数）。 */
    @GetMapping("/{id}")
    public R<SpuVo> detail(@PathVariable Long id) {
        return R.ok(merchantSpuService.detail(id, StpUtil.getLoginIdAsLong()));
    }

    /** 按规格模板批量生成 SKU（缺省=完整笛卡尔积；items 非空则可逐组合定价格）。 */
    @PostMapping("/{id}/generate-skus")
    public R<List<SkuVo>> generateSkus(@PathVariable Long id, @Valid @RequestBody SkuGenerateDto dto) {
        return R.ok(merchantSpuService.generateSkus(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /** 下架自建聚合 SPU 并级联下架其 SKU。 */
    @PostMapping("/{id}/offline")
    public R<Void> offline(@PathVariable Long id) {
        merchantSpuService.offline(id, StpUtil.getLoginIdAsLong());
        return R.ok();
    }
}
