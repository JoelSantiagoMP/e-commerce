/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL base de la API Spring Boot (incluye /api/v1). */
  readonly VITE_API_BASE_URL?: string
  /** Alias legacy; preferir VITE_API_BASE_URL. */
  readonly VITE_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
