import { request } from '../utils/request'
import type {
  Inquiry,
  MyInquiriesRequest,
  MyPriceListRequest,
  RtMyInquiries,
  RtPriceList,
  RtStoreFront,
  SubmitInquiryRequest,
} from '@cangchu/api-types'

/**
 * RT 买家端接口（uni 正式端；admin 内同名实现为过渡态 H5，仅 E2E/兼容用）。
 * 公开端点：进店 / 询价 / 我的价目 / 我的意向单——手机号即本地身份。
 * F6 RT 登录子波后新增：getWholesalerContact 需 RT 登录态（Authorization），
 * 服务端校验 RT 本人 + 询价单 CONFIRMED/COMPLETED（D-RT-01 / US-RT-04）。
 * 价格语义：匿名浏览只下发公开价；客户专属价请走「我的价目」（输入手机号）。
 */
export const rtApi = {
  /** 进店页聚合：店铺 + 店内 ACTIVE 批发商 + 在售 SKU（公开价 + 库存） */
  getStore(code: string) {
    return request<RtStoreFront>({ url: '/rt/store', params: { code } })
  },

  /** 提交询价：单事务建单 + 价格快照；返回 PENDING 单（含 docNo）。 */
  submitInquiry(data: SubmitInquiryRequest) {
    return request<Inquiry>({ url: '/rt/inquiry', method: 'POST', data })
  },

  /** 我的价目（专属价复购）：按批发商分组；仅尾号归属提示，无明文手机号。 */
  getMyPriceList(data: MyPriceListRequest) {
    return request<RtPriceList>({ url: '/rt/my-pricelist', method: 'POST', data })
  },

  /** 我的意向单（US-RT-04）：本店 + 本手机号全部询价单及状态（createdAt 倒序）。 */
  getMyInquiries(data: MyInquiriesRequest) {
    return request<RtMyInquiries>({ url: '/rt/my-inquiries', method: 'POST', data })
  },

  /**
   * F6（D-RT-01 / US-RT-04 验收）：RT 本人对已确认/已完成意向单查批发商联系方式。
   * 需 RT 登录态；后端走 /pii/phone-reveal?biz=RT_WHOLESALER&id={意向单 id}，
   * 仅回 phone 全号字段（PII 审计由后端统一落库）。
   */
  getWholesalerContact(inquiryId: string) {
    return request<{ phone: string }>({
      url: '/pii/phone-reveal',
      method: 'GET',
      params: { biz: 'RT_WHOLESALER', id: inquiryId },
    })
  },
}
