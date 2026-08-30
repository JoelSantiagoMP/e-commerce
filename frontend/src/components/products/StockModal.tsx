import { useEffect, useState } from 'react'
import { fetchVariantsByProduct, updateVariantStock } from '../../api/products'
import { getErrorMessage } from '../../api/client'
import { useToast } from '../ui/ToastProvider'
import { Modal } from '../ui/Modal'
import { Loader } from '../ui/Loader'
import type { Product, ProductVariant } from '../../types'

interface StockModalProps {
  product: Product | null
  open: boolean
  onClose: () => void
  onSaved: () => void
}

export function StockModal({ product, open, onClose, onSaved }: StockModalProps) {
  const { showSuccess, showError } = useToast()
  const [variants, setVariants] = useState<ProductVariant[]>([])
  const [loading, setLoading] = useState(false)
  const [savingId, setSavingId] = useState<number | null>(null)
  const [draftStock, setDraftStock] = useState<Record<number, number>>({})

  useEffect(() => {
    if (!open || !product) return

    const loadVariants = async () => {
      setLoading(true)
      try {
        const data = await fetchVariantsByProduct(product.id)
        setVariants(data)
        setDraftStock(Object.fromEntries(data.map((variant) => [variant.id, variant.stock])))
      } catch (error) {
        showError(getErrorMessage(error))
      } finally {
        setLoading(false)
      }
    }

    void loadVariants()
  }, [open, product, showError])

  const handleSave = async (variant: ProductVariant) => {
    const newStock = draftStock[variant.id]
    if (newStock === undefined || newStock < 0) {
      showError('El stock debe ser un número mayor o igual a 0')
      return
    }

    setSavingId(variant.id)
    try {
      await updateVariantStock(variant.id, newStock)
      showSuccess(`Stock actualizado para ${variant.sku}`)
      onSaved()
    } catch (error) {
      showError(getErrorMessage(error))
    } finally {
      setSavingId(null)
    }
  }

  return (
    <Modal open={open} title={product ? `Stock — ${product.name}` : 'Stock'} onClose={onClose}>
      {loading ? (
        <Loader label="Cargando variantes..." />
      ) : variants.length === 0 ? (
        <p className="text-sm text-slate-600">Este producto no tiene variantes activas.</p>
      ) : (
        <div className="space-y-3">
          {variants.map((variant) => (
            <div
              key={variant.id}
              className="rounded-xl border border-slate-200 p-4"
            >
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="font-semibold text-slate-900">{variant.sku}</p>
                  <p className="text-sm text-slate-500">
                    {[variant.size, variant.color].filter(Boolean).join(' · ') || 'Variante única'}
                  </p>
                </div>
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-700">
                  Actual: {variant.stock}
                </span>
              </div>

              <div className="flex flex-col gap-2 sm:flex-row">
                <input
                  type="number"
                  min={0}
                  inputMode="numeric"
                  value={draftStock[variant.id] ?? variant.stock}
                  onChange={(event) =>
                    setDraftStock((current) => ({
                      ...current,
                      [variant.id]: Number(event.target.value),
                    }))
                  }
                  className="min-h-12 flex-1 rounded-xl border border-slate-300 px-4 text-base outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                />
                <button
                  type="button"
                  disabled={savingId === variant.id}
                  onClick={() => void handleSave(variant)}
                  className="min-h-12 rounded-xl bg-indigo-600 px-5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-60"
                >
                  {savingId === variant.id ? 'Guardando...' : 'Guardar'}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </Modal>
  )
}
