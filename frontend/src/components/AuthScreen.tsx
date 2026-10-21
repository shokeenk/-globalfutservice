import type { ReactNode } from 'react'
import { Atmosphere } from './Atmosphere'
import { BrandBadge } from '../brand/Logo'
import { Card, Section } from './ui'

/**
 * The centred card the sign-in page uses, for the pages around it: forgot password and
 * choose a new password. Same crest, same light, so moving between them reads as one flow.
 */
export function AuthScreen({ heading, lead, children, footer }: {
  heading: string
  lead?: string
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <section className="relative isolate flex min-h-[calc(100dvh-72px)] items-center overflow-hidden">
      <Atmosphere intensity={0.7} />
      <Section className="relative rhythm-section">
        <div className="mx-auto max-w-md">
          <div className="mb-8 text-center">
            <BrandBadge size={96} aura className="mx-auto" priority />
            <h1 className="display mt-7 text-display-lg text-sheen">{heading}</h1>
            {lead && <p className="mt-3 text-body-sm text-chalk-muted">{lead}</p>}
          </div>
          <Card className="p-7 shadow-e3">{children}</Card>
          {footer && <div className="mt-6 text-center text-[13.5px] text-chalk-muted">{footer}</div>}
        </div>
      </Section>
    </section>
  )
}
