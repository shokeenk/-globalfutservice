import type { ReactNode } from 'react'
import { Atmosphere } from './Atmosphere'
import { Reveal } from '../motion/Reveal'

/**
 * The masthead every interior page opens with.
 *
 * <p>Before this existed, each page invented its own opening — different eyebrow
 * treatments, different heading sizes, different top padding, some with a lead
 * paragraph and some without. The result was thirteen pages that shared a colour
 * palette and nothing else, which is the specific failure that makes a site feel
 * assembled rather than designed.
 *
 * <p>One component, used everywhere, at a reduced light intensity so an interior
 * page is recognisably the same place as the homepage without competing with it.
 *
 * <p>Its padding was 80px above and 64px below, against about 145px of content — so
 * half the masthead was empty, on every interior page, before a reader reached
 * anything they came for. Now 48 and 40.
 * The bottom hairline is what separates the masthead from the working content
 * underneath, and it is the same rule the header uses on scroll.
 */
export function PageHeader({
  eyebrow, title, lead, aside, intensity = 0.45,
}: {
  eyebrow?: string
  title: ReactNode
  lead?: ReactNode
  /** Optional trailing block — a status, a price, a count. */
  aside?: ReactNode
  intensity?: number
}) {
  return (
    <section className="relative isolate overflow-hidden border-b border-ink-400">
      <Atmosphere intensity={intensity} motes={false} />
      <div className="relative mx-auto w-full max-w-[1320px] px-5 pb-8 pt-10 sm:px-8 lg:px-10 lg:pb-10 lg:pt-12">
        <div className="flex flex-col gap-8 lg:flex-row lg:items-end lg:justify-between">
          <Reveal className="max-w-3xl">
            {eyebrow && <p className="stamp">{eyebrow}</p>}
            <h1 className="display mt-5 text-balance text-display-xl text-sheen">{title}</h1>
            {lead && (
              <p className="measure mt-5 text-pretty text-body-lg text-chalk-muted">{lead}</p>
            )}
          </Reveal>
          {aside && (
            <Reveal delay={120} direction="left" className="shrink-0">
              {aside}
            </Reveal>
          )}
        </div>
      </div>
    </section>
  )
}
