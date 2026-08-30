import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchCategories, fetchProducts, fetchProductsByCategory } from '../../api/products'
import { getErrorMessage } from '../../api/client'
import { useToast } from '../ui/ToastProvider'
import { Loader } from '../ui/Loader'
import { StockModal } from './StockModal'
import type { Category, Product } from '../../types'

export function ProductsView() {
  const { showError } = useToast()
  const [products, setProducts] = useState<Product[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [categoryFilter, setCategoryFilter] = useState<string>('all')
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null)

  const loadProducts = useCallback(async () => {
    setLoading(true)
    try {
      const [categoriesData, productsData] = await Promise.all([
        fetchCategories(),
        categoryFilter === 'all'
          ? fetchProducts()
          : fetchProductsByCategory(Number(categoryFilter)),
      ])
      setCategories(categoriesData)
      setProducts(productsData)
    } catch (error) {
      showError(getErrorMessage(error))
    } finally {
      setLoading(false)
    }
  }, [categoryFilter, showError])

  useEffect(() => {
    void loadProducts()
  }, [loadProducts])

  const filteredProducts = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return products
    return products.filter(
      (product) =>
        product.name.toLowerCase().includes(term) ||
        product.categoryName.toLowerCase().includes(term) ||
        (product.description?.toLowerCase().includes(term) ?? false),
    )
  }, [products, search])

  return (
    <section className="space-y-5">
      <header>
        <h2 className="text-2xl font-bold text-slate-900">Inventario</h2>
        <p className="text-sm text-slate-600">
          Consulta productos, filtra por categoría y ajusta stock de variantes.
        </p>
      </header>

      <div className="grid gap-3 sm:grid-cols-[1fr_220px]">
        <input
          type="search"
          placeholder="Buscar por nombre, categoría o descripción..."
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          className="min-h-12 rounded-xl border border-slate-300 bg-white px-4 text-base outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
        />
        <select
          value={categoryFilter}
          onChange={(event) => setCategoryFilter(event.target.value)}
          className="min-h-12 rounded-xl border border-slate-300 bg-white px-4 text-base outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
        >
          <option value="all">Todas las categorías</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <Loader fullScreen label="Cargando inventario..." />
      ) : filteredProducts.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-white p-8 text-center text-slate-600">
          No se encontraron productos con los filtros actuales.
        </div>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
          <div className="hidden md:grid md:grid-cols-[2fr_1fr_1fr_auto] md:gap-4 md:border-b md:border-slate-200 md:bg-slate-50 md:px-4 md:py-3 md:text-xs md:font-semibold md:uppercase md:tracking-wide md:text-slate-500">
            <span>Producto</span>
            <span>Categoría</span>
            <span>Precio base</span>
            <span>Acciones</span>
          </div>

          <ul className="divide-y divide-slate-200">
            {filteredProducts.map((product) => (
              <li
                key={product.id}
                className="grid gap-3 p-4 md:grid-cols-[2fr_1fr_1fr_auto] md:items-center md:gap-4"
              >
                <div>
                  <p className="font-semibold text-slate-900">{product.name}</p>
                  <p className="text-sm text-slate-500 line-clamp-2">
                    {product.description || 'Sin descripción'}
                  </p>
                </div>
                <p className="text-sm text-slate-700">{product.categoryName}</p>
                <p className="text-sm font-medium text-slate-900">
                  ${product.basePrice.toFixed(2)}
                </p>
                <button
                  type="button"
                  onClick={() => setSelectedProduct(product)}
                  className="min-h-12 rounded-xl bg-indigo-600 px-4 text-sm font-semibold text-white hover:bg-indigo-700"
                >
                  Ajustar stock
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <StockModal
        product={selectedProduct}
        open={Boolean(selectedProduct)}
        onClose={() => setSelectedProduct(null)}
        onSaved={() => void loadProducts()}
      />
    </section>
  )
}
