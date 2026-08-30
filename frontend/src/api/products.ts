import { apiClient } from './client'
import type { Category, Product, ProductVariant } from '../types'

export async function fetchCategories(): Promise<Category[]> {
  const { data } = await apiClient.get<Category[]>('/products/categories')
  return data
}

export async function fetchProducts(): Promise<Product[]> {
  const { data } = await apiClient.get<Product[]>('/products')
  return data
}

export async function fetchProductsByCategory(categoryId: number): Promise<Product[]> {
  const { data } = await apiClient.get<Product[]>(`/products/category/${categoryId}`)
  return data
}

export async function fetchVariantsByProduct(productId: number): Promise<ProductVariant[]> {
  const { data } = await apiClient.get<ProductVariant[]>(`/products/${productId}/variants`)
  return data
}

export async function updateVariantStock(variantId: number, newStock: number): Promise<ProductVariant> {
  const { data } = await apiClient.patch<ProductVariant>(
    `/products/variants/${variantId}/stock`,
    null,
    { params: { newStock } },
  )
  return data
}
