interface LoaderProps {
  label?: string
  fullScreen?: boolean
}

export function Loader({ label = 'Cargando...', fullScreen = false }: LoaderProps) {
  const content = (
    <div className="flex flex-col items-center justify-center gap-3">
      <div
        className="h-10 w-10 animate-spin rounded-full border-4 border-indigo-200 border-t-indigo-600"
        role="status"
        aria-label={label}
      />
      <p className="text-sm font-medium text-slate-600">{label}</p>
    </div>
  )

  if (fullScreen) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">{content}</div>
    )
  }

  return content
}
