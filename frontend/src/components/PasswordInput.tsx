import { forwardRef, useState } from 'react'
import type { InputHTMLAttributes } from 'react'
import { LuEye, LuEyeOff } from 'react-icons/lu'
import { useT } from '../i18n'
import { Input } from './ui'

/**
 * A password field with a show/hide toggle.
 *
 * <p>The toggle only changes the input's type. The value stays in the form's state for as
 * long as the page is open and is never written anywhere else -- no storage, no cookie --
 * so "Remember me" remembers a session, never a password.
 *
 * <p>A real button whose label says what pressing it will do -- "Show password" or "Hide
 * password" -- so a screen reader hears the state as well as the action.
 */
export const PasswordInput = forwardRef<HTMLInputElement, Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>>(
  function PasswordInput({ className = '', ...rest }, ref) {
    const t = useT()
    const [visible, setVisible] = useState(false)
    return (
      <div className="relative">
        <Input ref={ref} type={visible ? 'text' : 'password'} className={`pr-11 ${className}`}
               autoCapitalize="none" autoCorrect="off" spellCheck={false} {...rest} />
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          aria-label={visible ? t.auth.hidePassword : t.auth.showPassword}
          className="absolute inset-y-0 right-0 flex w-11 items-center justify-center text-chalk-faint
                     transition-colors hover:text-chalk focus-visible:text-chalk"
        >
          {visible ? <LuEyeOff aria-hidden="true" /> : <LuEye aria-hidden="true" />}
        </button>
      </div>
    )
  },
)
