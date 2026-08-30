import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { ProductsView } from './components/products/ProductsView'
import { OrdersView } from './components/orders/OrdersView'
import { ToastProvider } from './components/ui/ToastProvider'

export default function App() {
  return (
    <ToastProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<AppLayout />}>
            <Route index element={<ProductsView />} />
            <Route path="orders" element={<OrdersView />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </ToastProvider>
  )
}
