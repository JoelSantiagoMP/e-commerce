import { useCallback, useEffect, useState } from 'react'
import { fetchOrders, updateOrderStatus } from '../../api/orders'
import { getErrorMessage } from '../../api/client'
import { useToast } from '../ui/ToastProvider'
import { Loader } from '../ui/Loader'
import type { Order, OrderStatus } from '../../types'

const STATUS_OPTIONS: { value: OrderStatus; label: string }[] = [
  { value: 'PENDING', label: 'Pendiente' },
  { value: 'CONFIRMED', label: 'Confirmada' },
  { value: 'SHIPPED', label: 'Enviada' },
  { value: 'CANCELLED', label: 'Cancelada' },
]

const STATUS_STYLES: Record<OrderStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-800',
  CONFIRMED: 'bg-blue-100 text-blue-800',
  SHIPPED: 'bg-emerald-100 text-emerald-800',
  CANCELLED: 'bg-rose-100 text-rose-800',
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('es-CO', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function OrdersView() {
  const { showSuccess, showError } = useToast()
  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)
  const [updatingId, setUpdatingId] = useState<number | null>(null)

  const loadOrders = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchOrders()
      setOrders(data)
    } catch (error) {
      showError(getErrorMessage(error))
    } finally {
      setLoading(false)
    }
  }, [showError])

  useEffect(() => {
    void loadOrders()
  }, [loadOrders])

  const handleStatusChange = async (orderId: number, status: OrderStatus) => {
    setUpdatingId(orderId)
    try {
      const updated = await updateOrderStatus(orderId, status)
      setOrders((current) =>
        current.map((order) => (order.id === orderId ? updated : order)),
      )
      showSuccess(`Orden #${orderId} actualizada a ${status}`)
    } catch (error) {
      showError(getErrorMessage(error))
    } finally {
      setUpdatingId(null)
    }
  }

  return (
    <section className="space-y-5">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-2xl font-bold text-slate-900">Órdenes</h2>
          <p className="text-sm text-slate-600">
            Pedidos recibidos vía Telegram o manuales. Actualiza el estado en tiempo real.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void loadOrders()}
          className="min-h-12 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50"
        >
          Refrescar
        </button>
      </header>

      {loading ? (
        <Loader fullScreen label="Cargando órdenes..." />
      ) : orders.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-white p-8 text-center text-slate-600">
          Aún no hay órdenes registradas.
        </div>
      ) : (
        <div className="space-y-4">
          {orders.map((order) => (
            <article
              key={order.id}
              className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:p-5"
            >
              <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                <div className="space-y-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="text-lg font-semibold text-slate-900">Orden #{order.id}</h3>
                    <span
                      className={`rounded-full px-3 py-1 text-xs font-semibold ${STATUS_STYLES[order.status]}`}
                    >
                      {order.status}
                    </span>
                  </div>
                  <p className="text-sm text-slate-600">
                    Cliente: <span className="font-medium text-slate-800">{order.customerName}</span>
                  </p>
                  <p className="text-sm text-slate-600">{formatDate(order.createdAt)}</p>
                  <p className="text-base font-semibold text-slate-900">
                    Total: ${order.totalAmount.toFixed(2)}
                  </p>
                </div>

                <div className="min-w-[220px]">
                  <label className="mb-2 block text-xs font-semibold uppercase tracking-wide text-slate-500">
                    Cambiar estado
                  </label>
                  <select
                    value={order.status}
                    disabled={updatingId === order.id}
                    onChange={(event) =>
                      void handleStatusChange(order.id, event.target.value as OrderStatus)
                    }
                    className="min-h-12 w-full rounded-xl border border-slate-300 bg-white px-4 text-base outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 disabled:opacity-60"
                  >
                    {STATUS_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <ul className="mt-4 space-y-2 border-t border-slate-100 pt-4">
                {order.items.map((item) => (
                  <li
                    key={item.id}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-slate-50 px-3 py-2 text-sm"
                  >
                    <span className="font-medium text-slate-800">
                      {item.productName} ({item.variantSku})
                    </span>
                    <span className="text-slate-600">
                      {item.quantity} × ${item.unitPrice.toFixed(2)}
                    </span>
                  </li>
                ))}
              </ul>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
