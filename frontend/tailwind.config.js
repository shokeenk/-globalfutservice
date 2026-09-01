/**
 * Design tokens for Global FUT Services.
 *
 * The palette is derived from the brand mark: a red circular badge. Rather than
 * spraying that red everywhere, it is treated as an accent with a very high
 * signal-to-noise ratio — it appears on exactly one thing per view (the primary
 * action, the live price, the active state) against a near-black field. That is
 * what makes a single-hue brand read as premium rather than as a warning label.
 *
 * The warm gold is reserved entirely for the rewards system, so "gold on screen"
 * always means "this is about your points" without anyone having to read a label.
 *
 * @type {import('tailwindcss').Config}
 */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      /*
       * Tailwind's preflight paints a default border colour on every element, so any
       * bare `border` with no colour of its own inherits this. It was still the old
       * lilac hairline, which is why borders across the site kept coming back purple
       * long after the palette was replaced — the value is one line and easy to miss
       * because nothing references it explicitly.
       */
      borderColor: { DEFAULT: '#E0E0E5' },
      /*
       * One surface colour, one accent, and a neutral ramp between them.
       *
       * This replaces a lilac system in which every "grey" carried a purple cast, six
       * different reds were used interchangeably, and yellow and blue sat on the page as
       * co-equal brands. The result read as a template with a theme applied. Here there
       * is exactly one brand colour and everything else is neutral, which is what lets
       * the red mean "act on this" wherever it appears.
       *
       * The red is the logo's own `#C1281B`, not a picked one. It also measures better
       * than the obvious alternative: white on `#DC2626` is 4.83:1 and drops to 4.32:1
       * as text on `gray-100` — under AA — where the logo red holds 5.85:1 and 5.24:1.
       */
      colors: {
        white: '#FFFFFF',

        gray: {
          50:  '#FAFAFA',   // faint band
          75:  '#F5F5F7',   // THE PAGE — one step below the cards, so a card has a ground
          100: '#F0F0F3',   // hover ground / deeper band
          200: '#E0E0E5',   // hairline
          300: '#D1D1D8',   // strong border, rings
          400: '#A1A1AA',   // disabled text, non-essential glyphs
        },

        /*
         * Surfaces and borders, keeping the names the codebase already uses — `ink` has
         * always been the surface ramp here (`border-ink-400` alone appears 52 times),
         * not a text colour. Repointed to the neutral scale, so the whole site moves
         * from lilac to white without touching a class name.
         *
         * On a white page a card cannot be a lighter grey than its ground, so cards are
         * white and earn their edge from a hairline and an elevation step instead. That
         * is the change that makes the page read as built rather than tinted.
         */
        ink: {
          DEFAULT: '#F5F5F7',   // the page itself
          800: '#FFFFFF',       // alternating band — white, against the grey page
          700: '#FFFFFF',       // raised surface — the workhorse
          600: '#FFFFFF',       // card
          500: '#F0F0F3',       // hover ground
          400: '#E0E0E5',       // hairline
          300: '#D1D1D8',       // strong border, rings
        },

        /*
         * One red. `600` is the fill and is safe as text on white (5.85:1); `700` is the
         * darker step for text on a tinted ground and for the pressed state.
         */
        red: {
          600: '#C1281B',
          700: '#AE2418',
          800: '#9A2016',
          tint: 'rgba(193,40,27,0.08)',
          ring: 'rgba(193,40,27,0.20)',
        },

        /* ---- compatibility aliases -------------------------------------------------
         * The codebase refers to surfaces and type by their old names in ~48 files.
         * Repointing the names is what converts the whole site in one move; renaming
         * every class instead would be a large diff whose only product is a rename.
         */
        paper: '#FFFFFF',
        chalk: {
          DEFAULT: '#111114',
          muted:   '#3F3F46',
          /*
           * The quiet step, set by its worst ground rather than its best.
           *
           * On flat white this could be much lighter and still pass. It is not used on
           * flat white: it is the helper text under form fields and inside plates, and
           * those grounds carry a wash — a tinted panel, a recessed surface — that takes
           * a percent or two of luminance out from under it. At #6B6B75 it measured 4.84
           * on the page and 4.45 on a washed card, which is a token that passes an audit
           * of the palette and fails the actual screen. This clears 4.5 on every ground
           * it lands on, with the page at 5.15 and the worst washed card at 4.87.
           */
          faint:   '#65656F',
        },
        brand: {
          50:  '#FDECEA',
          100: '#FADBD7',
          200: '#9A2016',
          300: '#C1281B',
          'on-dark': '#F2726A',   // the one red that works on a photograph
          400: '#AE2418',
          500: '#C1281B',
          600: '#AE2418',
          700: '#9A2016',
          900: '#7C1A11',
        },
        /*
         * The rewards accent — the one place a warm colour is allowed to mean something.
         *
         * This ramp had been flattened to greys on the theory that gold was a second
         * brand competing with red. That went too far: gold is not decoration here, it
         * is what the rewards system is *about* — wallet balances, points, tier rings,
         * the "POPULAR" chip. Neutralising it did not remove an accent, it removed the
         * only signal those figures had, and left five pages of coin values rendered in
         * the same grey as their labels.
         *
         * It is still not co-equal with red. Red is the only colour that means "act on
         * this"; gold never appears on a button, only on a value or the ground behind
         * one. And 500 is deliberately the same `sun` the trading service already wears,
         * so coins read as one colour across the site rather than two warm ones.
         *
         * The split between 400 and 500 is the load-bearing part. #FFC93C is a ground,
         * not an ink — it measures 1.66:1 against white, so type set in it is not
         * readable at any size. 400 is the same hue taken down until it carries text:
         * 5.93:1 on white, and 5.21:1 on the recessed plate, which is the darkest ground
         * it actually lands on.
         */
        gold: {
          300: '#FFF3D1',   // faint wash — tinted grounds and fills
          400: '#8A5A00',   // TEXT ONLY — values, eyebrows, numerals
          500: '#FFC93C',   // the accent itself — dots, rings, tints, glows (= sun)
          700: '#7A4E00',   // edge/ring against a solid 500 fill
        },
        /*
         * The two service accents, back by request.
         *
         * They are not co-equal brands: red remains the only colour that means "act on
         * this" — every button on the site is red and these two are never buttons. They
         * are card grounds, used in exactly three places (the services row, the picker
         * dialog and the quote cards), all driven from `serviceSkins.ts` so a service
         * wears one colour everywhere it appears.
         */
        deep: '#3A32A3',   // paper on this: 9.70:1
        sun:  '#FFC93C',   // ink on this:  12.27:1
        /*
         * Dark enough to be type on its own tint.
         *
         * `ok` is the text role here — `ok-solid` exists for fills — but at #15803D a
         * "Delivered" chip measured 4.27:1 against the 12% wash it sits on, which is
         * under AA for the 10.5px it is set at. A success colour that cannot be read on
         * a success background is the one place the palette had a genuine hole. This
         * clears 5.80:1 there, and white on it as a solid improves from 5.01 to 6.80.
         */
        ok: '#12692F',
        'ok-solid': '#15803D',
        warn: '#AE2418',
        platform: {
          pc:   '#111114',
          ps:   '#3F3F46',
          xbox: '#71717A',
        },
      },

      /*
       * Archivo for display, Schibsted Grotesk for text. Both variable, both
       * self-hosted — see the note at the top of index.css for why neither is
       * Inter. The fallbacks matter more than usual here: `font-display: swap`
       * means the first paint is in the system face, so the stack is ordered to
       * land on something with similar proportions rather than on Times.
       */
      fontFamily: {
        /*
         * One family for both roles.
         *
         * Archivo and Schibsted Grotesk were a display/text pair — two grotesques
         * chosen to sit together. Poppins is a geometric sans doing both jobs, so
         * the separation between a heading and a paragraph now comes from weight
         * and size alone rather than from a change of voice. Both keys are kept so
         * `font-display` and `font-sans` still mean something at the call sites, and
         * a second face can be reintroduced in one place.
         */
        display: ['Poppins', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        sans: ['Poppins', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      letterSpacing: {
        tightest: '-0.045em',
      },
      /*
       * Optical type scale.
       *
       * Tracking is baked into each step because letter-spacing is a function of
       * size, not a global constant. Large type set at default tracking looks loose
       * and amateur; small caps set at default tracking looks cramped. Every step
       * here carries the tracking and leading that size actually wants, so a heading
       * is `text-display-lg` and nothing else — no stack of tracking utilities at
       * each call site, quietly diverging.
       */
      fontSize: {
        // One step above 2xl, for the homepage headline only.
        'display-3xl': ['clamp(2.9rem,6.9vw,5rem)', { lineHeight: '0.98', letterSpacing: '-0.04em' }],
        'display-2xl': ['clamp(2.75rem,6.4vw,4.5rem)', { lineHeight: '0.95', letterSpacing: '-0.042em' }],
        'display-xl':  ['clamp(2.25rem,4.6vw,3.25rem)', { lineHeight: '1.0',  letterSpacing: '-0.036em' }],
        'display-lg':  ['clamp(1.75rem,3.2vw,2.375rem)', { lineHeight: '1.08', letterSpacing: '-0.03em' }],
        'display-md':  ['1.375rem', { lineHeight: '1.2',  letterSpacing: '-0.022em' }],
        'display-sm':  ['1.0625rem', { lineHeight: '1.3', letterSpacing: '-0.014em' }],
        // Body sizes get slightly positive tracking at small sizes, which is what
        // keeps 12px legible rather than dense.
        'body-lg': ['1.0625rem', { lineHeight: '1.65', letterSpacing: '-0.005em' }],
        'body':    ['0.9375rem', { lineHeight: '1.65', letterSpacing: '0' }],
        'body-sm': ['0.8438rem', { lineHeight: '1.6',  letterSpacing: '0.004em' }],
        'micro':   ['0.75rem',   { lineHeight: '1.5',  letterSpacing: '0.01em' }],
      },

      /*
       * One elevation ladder, tuned for a white page.
       *
       * Replaces ten hand-written arbitrary shadows, each slightly different, which is
       * why depth looked accidental. Two layers per step: a tight contact shadow that
       * sits the element on the page, and a wider ambient one that gives it height. A
       * single blur does neither convincingly.
       *
       * `glow` is not depth. It is the primary button looking lit, and it is the one
       * thing that stops a red-on-white page reading flat.
       */
      boxShadow: {
        xs: '0 1px 2px rgba(17,17,20,0.05)',
        e1: '0 1px 2px rgba(17,17,20,0.05)',
        sm: '0 1px 3px rgba(17,17,20,0.09), 0 4px 12px rgba(17,17,20,0.06)',
        e2: '0 1px 3px rgba(17,17,20,0.09), 0 4px 12px rgba(17,17,20,0.06)',
        md: '0 2px 6px rgba(17,17,20,0.10), 0 12px 28px rgba(17,17,20,0.10)',
        e3: '0 2px 6px rgba(17,17,20,0.10), 0 12px 28px rgba(17,17,20,0.10)',
        lg: '0 4px 10px rgba(17,17,20,0.12), 0 24px 56px rgba(17,17,20,0.16)',
        glow: '0 8px 20px rgba(193,40,27,0.25)',
        'glow-lg': '0 10px 28px rgba(193,40,27,0.32)',
        pressed: 'inset 0 2px 4px rgba(17,17,20,0.10)',

        /*
         * For anything floating on the hero photograph.
         *
         * The ladder above is tuned for a white page, where a 6%-black shadow is plenty.
         * Over a dark image the same shadow is invisible — there is nothing lighter
         * behind it to darken — so a panel on the photo sat completely flat. These are
         * the same two-layer construction at the strength a dark ground actually needs,
         * plus a hairline of light along the top edge: that inset highlight is what
         * reads as a raised object catching the light, and it is doing more of the 3D
         * work than the drop shadow is.
         */
        media: 'inset 0 1px 0 0 rgb(255 255 255 / 0.22), 0 2px 6px rgb(0 0 0 / 0.32), 0 24px 60px -14px rgb(0 0 0 / 0.62)',
        'media-lg': 'inset 0 1px 0 0 rgb(255 255 255 / 0.28), 0 4px 12px rgb(0 0 0 / 0.38), 0 40px 90px -20px rgb(0 0 0 / 0.72)',
        card: '0 1px 3px rgba(17,17,20,0.09), 0 4px 12px rgba(17,17,20,0.06)',
        lift: '0 4px 10px rgba(17,17,20,0.12), 0 24px 56px rgba(17,17,20,0.16)',
      },

      /*
       * Tailwind's default duration scale jumps 300 → 500, and 400ms is exactly
       * where a medium-distance travel on the expo curve wants to land: 300 clips
       * the tail, 500 starts to feel like waiting. One value, added because the gap
       * is real rather than because a class name was convenient.
       */
      transitionDuration: {
        400: '400ms',
      },

      transitionTimingFunction: {
        /*
         * Three curves, and only three.
         *
         * `out-expo` is the long decelerating arrival that makes an interface feel
         * considered rather than snappy — it is the single biggest difference between
         * a default `ease` transition and one that reads as designed. `spring` gives
         * a slight overshoot for things that should feel physical. `sharp` is for
         * exits, which per Material should be faster than entrances so the interface
         * never feels like it is holding you up.
         */
        'out-expo': 'cubic-bezier(0.16, 1, 0.3, 1)',
        spring: 'cubic-bezier(0.34, 1.4, 0.64, 1)',
        sharp: 'cubic-bezier(0.4, 0, 1, 1)',
      },
      backgroundImage: {
        // A hairline grid, barely there. On white it has to be far fainter than the
        // black-theme version or it reads as graph paper.
        'grid-faint':
          'linear-gradient(rgba(29,22,54,0.035) 1px, transparent 1px),' +
          'linear-gradient(90deg, rgba(29,22,54,0.035) 1px, transparent 1px)',
        // The primary button. Light at the top-left, deepening to the pressed red —
        // the same ramp the logo's ink would take under a light source.
        /*
         * Every stop has to take a cream label, not just the middle one.
         *
         * Two stops rather than three, so every pixel between them is a blend the
         * browser computes from two palette values — a hand-written midpoint is where
         * an unsanctioned hue creeps in, and it creeps in invisibly because the ends
         * still look correct. Both stops here clear 4.51:1 and 6.78:1 under cream.
         *
         * The hover ramp descends the same red rather than climbing toward a second
         * hue: there is no second hue, and every stop still carries a paper label.
         */
        'brand-sheen':
          'linear-gradient(135deg, #C1281B 0%, #9A2016 100%)',
        'brand-sheen-hot':
          'linear-gradient(135deg, #9A2016 0%, #7C1A11 100%)',
        /*
         * `paper-wash` was removed rather than repointed. It was a lilac radial left
         * from the retired palette, unreferenced by any component, and invisible to a
         * sweep of the built CSS because Tailwind only emits the utilities in use. A
         * named token holding a colour the site no longer has is worse than no token:
         * the next person reaching for "a soft paper wash" gets lilac and no warning.
         * Section grounds now come from the ink ramp, which is where they belong.
         */
      },
      backgroundSize: {
        grid: '56px 56px',
      },
      /*
       * Radius, as a decision rather than a default.
       *
       * Rounding everything to the same 16px is the single loudest tell of a
       * template. Real product design varies it by what the element *is*: chrome
       * and data surfaces are nearly square because they read as precise; things
       * you press are softened because they read as physical; only pills are fully
       * round. `edge` is the default for panels and it is deliberately tight.
       */
      borderRadius: {
        edge: '4px',      // data surfaces, table shells, HUD plates
        panel: '10px',    // cards, dialogs, media
        press: '12px',    // buttons and other pressables
      },

      keyframes: {
        rise: {
          '0%': { opacity: '0', transform: 'translateY(10px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        pulseDot: {
          '0%, 100%': { opacity: '1', transform: 'scale(1)' },
          '50%': { opacity: '0.45', transform: 'scale(0.85)' },
        },
        shimmer: {
          '100%': { transform: 'translateX(100%)' },
        },

        /*
         * Atmosphere.
         *
         * Floodlights are never perfectly steady — there is always a slight swell as
         * air moves through the beam. `breathe` is that, at an amplitude low enough
         * that you notice the room is alive without ever catching the loop. The two
         * light layers run at different periods and neither divides the other, so
         * they never resynchronise into a visible pulse.
         */
        breathe: {
          '0%, 100%': { opacity: '0.75', transform: 'scale(1)' },
          '50%': { opacity: '1', transform: 'scale(1.06)' },
        },
        drift: {
          '0%': { transform: 'translate3d(-14%, 0, 0)' },
          '100%': { transform: 'translate3d(14%, 0, 0)' },
        },
        /* A light sweeping across a surface — used on primary CTAs, once, on hover. */
        sweep: {
          '0%': { transform: 'translateX(-120%) skewX(-18deg)' },
          '100%': { transform: 'translateX(220%) skewX(-18deg)' },
        },
        /*
         * The indeterminate band on a working button. It clears both edges — the
         * element is a third of the width, so -100% parks it fully off the left and
         * 300% fully off the right — which is what stops it looking like a bar that
         * bounces off the sides.
         */
        band: {
          '0%': { transform: 'translateX(-100%)' },
          '100%': { transform: 'translateX(300%)' },
        },
        /* Continuous horizontal scroll for the ticker rail. */
        ticker: {
          '0%': { transform: 'translate3d(0, 0, 0)' },
          '100%': { transform: 'translate3d(-50%, 0, 0)' },
        },
        /* A scroll cue that falls and fades, like something dropping down the page. */
        cue: {
          '0%': { opacity: '0', transform: 'translateY(-6px)' },
          '35%': { opacity: '1' },
          '100%': { opacity: '0', transform: 'translateY(10px)' },
        },
      },
      animation: {
        rise: 'rise 0.5s cubic-bezier(0.22, 1, 0.36, 1) both',
        'pulse-dot': 'pulseDot 2s ease-in-out infinite',
        breathe: 'breathe 11s ease-in-out infinite',
        'breathe-slow': 'breathe 17s ease-in-out infinite',
        drift: 'drift 46s ease-in-out infinite alternate',
        sweep: 'sweep 0.9s cubic-bezier(0.16, 1, 0.3, 1)',
        band: 'band 1.15s cubic-bezier(0.45, 0, 0.55, 1) infinite',
        // The brand ring, turning. Slower than a conventional spinner because the
        // four breaks already carry the motion — at spinner speed they smear into a
        // solid circle and the whole point of using the mark is lost.
        'spin-slow': 'spin 2.4s linear infinite',
        ticker: 'ticker 42s linear infinite',
        cue: 'cue 2.4s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
