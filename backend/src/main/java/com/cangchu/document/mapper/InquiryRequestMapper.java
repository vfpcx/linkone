package com.cangchu.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.CustomerAggRow;
import com.cangchu.document.entity.InquiryRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InquiryRequestMapper extends BaseMapper<InquiryRequest> {

    /**
     * wa 客户列表聚合（C3 · 24-p5-c-c3 §4.1）：当前租户 + 指定 wholesaler 集下，
     * 按 (wholesaler_id, rt_phone_hmac) 归并询价买家并统计。
     *
     * <p>分页由 PaginationInnerInterceptor 处理（count 自动包子查询）；TenantLine 对 inquiry_requests
     * 追加的 tenant_id 谓词与显式条件并存无副作用。rt_phone_cipher 取组内任一行（同明文不同密文，
     * 解密结果一致）。wholesaler 归属收敛（K-7）在调用方完成，本 SQL 不承载。
     */
    @Select("""
            <script>
            SELECT rt_phone_hmac,
                   wholesaler_id,
                   COUNT(*)         AS inquiry_count,
                   MAX(created_at)  AS last_inquiry_at,
                   MAX(confirmed_at) AS last_confirmed_at,
                   MAX(id)          AS last_inquiry_id,
                   MAX(rt_phone_cipher) AS rt_phone_cipher
            FROM inquiry_requests
            WHERE tenant_id = #{tenantId}
              AND rt_phone_hmac IS NOT NULL
              AND wholesaler_id IN
              <foreach collection="wholesalerIds" item="wid" open="(" separator="," close=")">#{wid}</foreach>
            GROUP BY wholesaler_id, rt_phone_hmac
            ORDER BY last_inquiry_at DESC
            </script>
            """)
    Page<CustomerAggRow> pageCustomerAgg(Page<CustomerAggRow> page,
                                          @Param("tenantId") Long tenantId,
                                          @Param("wholesalerIds") List<Long> wholesalerIds);
}
