import { ReactNode } from 'react'

export default function ContentArea({ children }: { children: ReactNode }) {
  return (
    <main id="main-content" className="min-w-0 flex-1 lg:ml-60">
      <div className="mx-auto w-full max-w-[1480px] px-4 py-6 sm:px-6 lg:px-10 lg:py-9">{children}</div>
    </main>
  )
}
