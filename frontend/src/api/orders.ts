import { apiClient } from './client'
import type { Order, OrderStatus } from '../types'

export async function fetchOrders(): Promise<Order[]> {
  const { data } = await apiClient.get<Order[]>('/orders')
  return data
}

export async function updateOrderStatus(orderId: number, status: OrderStatus): Promise<Order> {
  const { data } = await apiClient.patch<Order>(`/orders/${orderId}/status`, null, {
    params: { status },
  })
  return data
}
