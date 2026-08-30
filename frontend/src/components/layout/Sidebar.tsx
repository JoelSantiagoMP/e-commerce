import { NavLink } from 'react-router-dom'

interface SidebarProps {
  collapsed: boolean
  onToggle: () => void
  onNavigate?: () => void
}

const navItems = [
  { to: '/', label: 'Inventario', icon: '📦' },
  { to: '/orders', label: 'Órdenes', icon: '🧾' },
]

export function Sidebar({ collapsed, onToggle, onNavigate }: SidebarProps) {
  return (
    <aside
      className={`flex h-full flex-col border-r border-slate-200 bg-white transition-all duration-200 ${
        collapsed ? 'w-20' : 'w-64'
      }`}
    >
      <div className="flex items-center justify-between gap-2 border-b border-slate-200 p-4">
        {!collapsed && (
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-indigo-600">Tienda POS</p>
            <h1 className="text-lg font-bold text-slate-900">Admin Panel</h1>
          </div>
        )}
        <button
          type="button"
          onClick={onToggle}
          className="min-h-11 min-w-11 rounded-xl bg-slate-100 text-lg hover:bg-slate-200"
          aria-label={collapsed ? 'Expandir menú' : 'Colapsar menú'}
        >
          {collapsed ? '→' : '←'}
        </button>
      </div>

      <nav className="flex flex-1 flex-col gap-2 p-3">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            onClick={onNavigate}
            className={({ isActive }) =>
              `flex min-h-12 items-center gap-3 rounded-xl px-3 text-sm font-semibold transition-colors ${
                isActive
                  ? 'bg-indigo-600 text-white'
                  : 'text-slate-700 hover:bg-slate-100'
              }`
            }
          >
            <span className="text-xl">{item.icon}</span>
            {!collapsed && <span>{item.label}</span>}
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
