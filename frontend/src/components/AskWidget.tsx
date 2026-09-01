import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useT } from '../i18n'
import { useFaqFlat } from '../content/faq'

/**
 * The help centre, as a conversation.
 *
 * <p><b>Scripted, not generative — and that is the point, not a shortcut.</b> Every answer
 * here is one the business has already committed to in writing: the guarantee window, the
 * refund rule, what happens to a customer's sign-in. A language model asked those questions
 * will occasionally invent a number, and an invented refund window on a page that also
 * takes payments is not a bad answer, it is a contractual problem. This widget can only
 * say sentences somebody wrote.
 *
 * <p>It reads from the same {@code t.help.*} entries the help page renders, so the two can
 * never disagree, and it inherits all three languages for free.
 *
 * <p>If a real model is added later, it belongs behind this same surface — retrieval over
 * these answers, hard escalation on anything about money, and the "talk to a person" route
 * always visible. The UI would not change.
 */

type Message =
  | { role: 'assistant'; text: string; id: string }
  | { role: 'user'; text: string; id: string }

type Topic = { id: string; question: string; answer: string }

/** How long the assistant "thinks" before answering. */
const TYPING_MS = 520

export function AskWidget() {
  const t = useT()

  const faq = useFaqFlat()
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<Message[]>([])
  const [typing, setTyping] = useState(false)
  const [answered, setAnswered] = useState<string[]>([])
  const [handedOff, setHandedOff] = useState(false)

  const panelRef = useRef<HTMLDivElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const launcherRef = useRef<HTMLButtonElement>(null)

  /*
   * One list, shared with the help page and the site search.
   *
   * This used to rebuild the questions itself, which is how it ended up serving nine
   * that the help page no longer had. `useFaq` is now the only place the set is
   * written down, so the widget cannot answer a question the page does not show.
   */
  const topics: Topic[] = useMemo(
    () => faq.map((item) => ({ id: item.key, question: item.question, answer: item.answer })),
    [faq],
  )

  const remaining = topics.filter((topic) => !answered.includes(topic.id))

  // Open with the greeting already there, so the panel is never an empty box.
  useEffect(() => {
    if (open && messages.length === 0) {
      setMessages([{ role: 'assistant', text: t.chat.greeting, id: 'greeting' }])
    }
  }, [open, messages.length, t.chat.greeting])

  // Follow the conversation as it grows.
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, typing])

  /*
   * Anything on the site can open this panel by dispatching `gfs:open-ask`.
   *
   * The widget is mounted at the app root, outside the router, so a section on the
   * homepage cannot reach its state through props without threading a setter through
   * every page in between. A window event keeps that coupling at zero: callers do
   * not import the widget, and the widget does not know callers exist.
   */
  useEffect(() => {
    const onOpen = () => setOpen(true)
    window.addEventListener('gfs:open-ask', onOpen)
    return () => window.removeEventListener('gfs:open-ask', onOpen)
  }, [])

  // Escape closes, and focus returns to the launcher rather than the page top.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
        launcherRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  const ask = useCallback((topic: Topic) => {
    setMessages((prev) => [
      ...prev,
      { role: 'user', text: topic.question, id: `q-${topic.id}` },
    ])
    setTyping(true)

    /*
     * A deliberate pause before answering. Instant text reads as a lookup table; a
     * beat reads as a reply. It is theatre, but it is the theatre the interface is
     * borrowing, and without it the bubbles feel like an accordion wearing a costume.
     */
    window.setTimeout(() => {
      setTyping(false)
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', text: topic.answer, id: `a-${topic.id}` },
      ])
      setAnswered((prev) => [...prev, topic.id])
    }, TYPING_MS)
  }, [])

  const handOff = useCallback(() => {
    setMessages((prev) => [
      ...prev,
      { role: 'user', text: t.chat.talkToHuman, id: 'q-human' },
    ])
    setTyping(true)
    window.setTimeout(() => {
      setTyping(false)
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', text: t.chat.humanReply, id: 'a-human' },
      ])
      setHandedOff(true)
    }, TYPING_MS)
  }, [t.chat.talkToHuman, t.chat.humanReply])

  return (
    <>
      {/* ---------------------------------------------------------- launcher --- */}
      <button
        ref={launcherRef}
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-controls="ask-panel"
        aria-label={t.chat.launcher}
        className={[
          'fixed bottom-5 right-5 z-50 grid h-14 w-14 place-items-center rounded-full',
          'bg-brand-sheen text-paper shadow-glow transition-transform duration-200',
          'hover:scale-105 focus:outline-none focus:ring-2 focus:ring-brand-400',
          'focus:ring-offset-2 focus:ring-offset-ink sm:h-[58px] sm:w-[58px]',
          open ? 'scale-95' : '',
        ].join(' ')}
      >
        {open ? <CloseIcon /> : <ChatIcon />}
        {/* A quiet presence cue. Not a fake unread badge — there is no message
            waiting, and inventing one to farm clicks is a dark pattern. */}
        {!open && (
          <span
            aria-hidden="true"
            className="absolute -right-0.5 -top-0.5 h-3.5 w-3.5 rounded-full border-2
                       border-ink bg-ok animate-pulse-dot"
          />
        )}
      </button>

      {/* ------------------------------------------------------------- panel --- */}
      {open && (
        <div
          id="ask-panel"
          ref={panelRef}
          role="dialog"
          aria-modal="false"
          aria-label={t.chat.assistant}
          className="animate-rise fixed bottom-24 right-5 z-50 flex max-h-[min(620px,calc(100vh-8rem))]
                     w-[min(380px,calc(100vw-2.5rem))] flex-col overflow-hidden rounded-panel
                     border border-ink-400 bg-paper shadow-e3 backdrop-blur-2xl backdrop-saturate-150"
        >
          <header className="flex items-center gap-3 border-b border-ink-400 bg-paper px-4 py-3.5">
            <span
              aria-hidden="true"
              className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-brand-sheen
                         text-[13px] font-bold text-paper"
            >
              FUT
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13.5px] font-semibold text-chalk">
                {t.chat.assistant}
              </p>
              <p className="flex items-center gap-1.5 text-[11.5px] text-chalk-faint">
                <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-ok" />
                {t.chat.status}
              </p>
            </div>
            <button
              type="button"
              onClick={() => {
                setOpen(false)
                launcherRef.current?.focus()
              }}
              aria-label={t.chat.close}
              className="grid h-8 w-8 place-items-center rounded-lg text-chalk-muted
                         transition-colors hover:bg-ink-500 hover:text-chalk"
            >
              <CloseIcon small />
            </button>
          </header>

          <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-4">
            {messages.map((message) => (
              <Bubble key={message.id} role={message.role} text={message.text} />
            ))}

            {typing && <TypingBubble label={t.chat.typing} />}

            {handedOff && !typing && (
              <Link
                to="/support"
                onClick={() => setOpen(false)}
                className="block rounded-press bg-brand-sheen px-4 py-2.5 text-center text-[13px]
                           font-semibold text-paper transition-opacity hover:opacity-90"
              >
                {t.chat.openSupport}
              </Link>
            )}
          </div>

          {/* ------------------------------------------------------ suggestions --- */}
          <div className="border-t border-ink-400 bg-paper px-4 py-3">
            {remaining.length > 0 && !typing && (
              <>
                <p className="eyebrow mb-2 text-[10.5px]">
                  {messages.length > 1 ? t.chat.anythingElse : t.chat.suggestions}
                </p>
                <div className="flex max-h-[132px] flex-wrap gap-1.5 overflow-y-auto">
                  {remaining.slice(0, 4).map((topic) => (
                    <button
                      key={topic.id}
                      type="button"
                      onClick={() => ask(topic)}
                      className="rounded-full border border-ink-300 bg-ink-600 px-3 py-1.5
                                 text-left text-[12px] text-chalk-muted transition-colors
                                 hover:border-brand-500/50 hover:text-chalk"
                    >
                      {topic.question}
                    </button>
                  ))}
                </div>
              </>
            )}

            <div className="mt-2.5 flex items-center justify-between gap-3">
              {!handedOff ? (
                <button
                  type="button"
                  onClick={handOff}
                  className="text-[12px] font-medium text-brand-400 transition-colors
                             hover:text-brand-200"
                >
                  {t.chat.talkToHuman}
                </button>
              ) : (
                <span />
              )}
              <Link
                to="/help"
                onClick={() => setOpen(false)}
                className="text-[12px] text-chalk-faint transition-colors hover:text-chalk-muted"
              >
                {t.chat.readFull}
              </Link>
            </div>

            {/* Says plainly that this is automated. A widget that lets someone believe
                they are talking to a person, then answers a refund question, is the
                one thing this pattern must not do. */}
            <p className="mt-2.5 border-t border-ink-400 pt-2.5 text-[10.5px] leading-relaxed
                          text-chalk-faint">
              {t.chat.automated}
            </p>
          </div>
        </div>
      )}
    </>
  )
}

/* ---------------------------------------------------------------- bubbles --- */

export function Bubble({ role, text }: { role: 'assistant' | 'user'; text: string }) {
  const assistant = role === 'assistant'
  return (
    <div className={`flex ${assistant ? 'justify-start' : 'justify-end'}`}>
      <p
        className={[
          'max-w-[85%] rounded-2xl px-3.5 py-2.5 text-[13px] leading-relaxed',
          assistant
            ? 'rounded-bl-md bg-ink-700 text-chalk-muted'
            : 'rounded-br-md bg-brand-sheen font-medium text-paper',
        ].join(' ')}
      >
        {text}
      </p>
    </div>
  )
}

function TypingBubble({ label }: { label: string }) {
  return (
    <div className="flex justify-start">
      <p
        className="flex items-center gap-1 rounded-2xl rounded-bl-md bg-ink-700 px-4 py-3"
        role="status"
        aria-label={label}
      >
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            aria-hidden="true"
            className="h-1.5 w-1.5 rounded-full bg-chalk-faint animate-pulse-dot"
            // Staggered so it reads as a wave rather than three lights blinking together.
            style={{ animationDelay: `${i * 180}ms`, animationDuration: '1.1s' }}
          />
        ))}
      </p>
    </div>
  )
}

/* ------------------------------------------------------------------ icons --- */

function ChatIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.9 8.9 0 0 1-3.8-.9L3 21l1.9-5.1A8.4 8.4 0 0 1 12 3a8.4 8.4 0 0 1 9 8.5Z" />
    </svg>
  )
}

function CloseIcon({ small = false }: { small?: boolean }) {
  const size = small ? 15 : 20
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
      <path d="M18 6 6 18" />
      <path d="m6 6 12 12" />
    </svg>
  )
}
