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
 * RT 买家端接口（uni 正式端；admin 内同名实现为过渡态 H5）。
 * 全部为公开端点：进店 / 询价 / 我的价目 / 我的意向单。无登录态，手机号即本地身份。
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
}
