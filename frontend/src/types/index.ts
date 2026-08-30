export interface Category {
  id: number
  name: string
  description: string | null
  isActive: boolean
}

export interface Product {
  id: number
  categoryId: number
  categoryName: string
  name: string
  description: string | null
  basePrice: number
  isActive: boolean
  createdAt: string
}

export interface ProductVariant {
  id: number
  productId: number
  sku: string
  size: string | null
  color: string | null
  stock: number
  priceOverride: number | null
  isActive: boolean
}

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'SHIPPED' | 'CANCELLED'

export interface OrderItem {
  id: number
  productVariantId: number
  productName: string
  variantSku: string
  quantity: number
  unitPrice: number
}

export interface Order {
  id: number
  customerId: number
  customerName: string
  totalAmount: number
  status: OrderStatus
  createdAt: string
  items: OrderItem[]
}

export interface ApiError {
  message?: string
  status?: number
}
