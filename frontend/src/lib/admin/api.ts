import { api, getToken } from '../api.ts'

export const ADMIN_SESSION = { basePath: '/api/admin', tokenKey: 'imagehub.admin.token', expiredEvent: 'imagehub:admin-session-expired' }
export const getAdminToken = () => getToken(ADMIN_SESSION)
export const adminApi = <T>(path: string, init: RequestInit = {}) => api<T>(path, init, ADMIN_SESSION)

export type Admin = { id: string; username: string }
export type AdminUser = { id: string; username: string; email: string; createTime: string | null; remaining: number | null }
export type UserPage = { records: AdminUser[]; total: number; current: number; size: number; pages: number }
