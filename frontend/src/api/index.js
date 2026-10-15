/* 业务接口清单（与后端 Controller 一一对应，沿用旧版 Backend 语义） */
import { api } from './http'

export const Backend = {
  /* 认证（401 = 凭证错误，不做登出跳转） */
  register: (b) => api('POST', '/api/auth/register', b, { skipAuthRedirect: true }),
  login:    (b) => api('POST', '/api/auth/login', b, { skipAuthRedirect: true }),

  /* 账本 */
  createLedger: (name) => api('POST', '/api/ledgers', { name }),
  myLedgers: () => api('GET', '/api/ledgers'),
  invite: (lid, username) => api('POST', `/api/ledgers/${lid}/members`, { username }),
  members: (lid) => api('GET', `/api/ledgers/${lid}/members`),

  /* 支出 */
  addExpense: (lid, req) => api('POST', `/api/ledgers/${lid}/expenses`, req),
  expensesPage: (lid, page, size) => api('GET', `/api/ledgers/${lid}/expenses?page=${page}&size=${size}`),
  deleteExpense: (id, version) => api('DELETE', `/api/expenses/${id}?version=${version}`),

  /* 结算 */
  balances: (lid) => api('GET', `/api/ledgers/${lid}/balances`),
  plan: (lid) => api('GET', `/api/ledgers/${lid}/settlement-plan`),
  executePlan: (lid) => api('POST', `/api/ledgers/${lid}/settlements/execute-plan?confirm=true`),
}
