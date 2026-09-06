/**
 * The canonical dictionary.
 *
 * English is the source of truth and every other locale is typed against it, so a
 * missing or misspelled key is a compile error rather than a blank space on a live
 * page. That is the whole reason this is a typed object and not a JSON file: a
 * translation that silently falls back to the key is how "checkout.confirm" ends up
 * printed on a button in production.
 *
 * Keys are grouped by where they appear, not by language feature. Someone editing the
 * header should find every header string together.
 */
const en = {
  nav: {
    trading: 'Trading',
    boosting: 'Boosting',
    coaching: 'Coaching',
    rewards: 'Rewards',
    track: 'Track order',
    faqs: 'FAQs',
    tradersOnline: 'Traders online',
    myAccount: 'My account',
    console: 'Console',
    signIn: 'Sign in',
    signOut: 'Sign out',
    buyCoins: 'Buy coins',
    openMenu: 'Open menu',
    closeMenu: 'Close menu',
    skipToContent: 'Skip to content',
    home: 'Global FUT Services home',
    language: 'Language',
    currency: 'Currency',
  },

  footer: {
    tagline:
      'A trading and coaching service for EA FC. We work the transfer market on your ' +
      'account; the coins stay with you.',
    services: 'Services',
    menu: 'Menu',
    legal: 'Legal',
    follow: 'Follow',
    rewards: 'Rewards',
    help: 'Help centre',
    support: 'Support',
    futClasses: 'FUT classes',
    cards: 'Player cards',
    terms: 'Terms of service',
    privacy: 'Privacy policy',
    aml: 'AML & KYC',
    about: 'About us',
    contact: 'Contact us',
    refund: 'Refund policy',
    cancellation: 'Cancellation policy',
    shipping: 'Shipping policy',
    rights:
      'In-game currency, items and player cards are the property of Electronic Arts ' +
      'Inc. We are not affiliated with EA.',
    // Shown when the interface is translated but the contracts are not. Saying which
    // version governs is not a courtesy — it is what stops a translated summary being
    // argued as a term of the contract.
    legalLanguageNotice:
      'Our Terms, Privacy Policy and AML & KYC policy are published in English, and the ' +
      'English text is the version that governs.',
  },

  common: {
    loading: 'Loading…',
    tryAgain: 'Try again',
    cancel: 'Cancel',
    close: 'Close',
    comingSoon: 'Coming soon',
  },

  /*
   * Shown when the storefront's currency is not the one loyalty settles in.
   *
   * The engine refuses to earn or redeem points outside the loyalty currency, so the
   * copy has to say so. A rewards page that advertises a rebate the pricing engine
   * will not pay is the exact drift this dictionary exists to prevent.
   */
  loyalty: {
    otherCurrencyTitle: (loyalty: string): string => `Reward points settle in ${loyalty}`,
    otherCurrencyBody: (shown: string, loyalty: string): string =>
      `Orders priced in ${shown} do not earn points, and a points balance cannot be ` +
      `spent against them. Switch the currency back to ${loyalty} to earn and redeem.`,
  },

  /*
   * Chrome for the testimonials section. The quotes themselves live in
   * `data/testimonials.ts` and are deliberately not in here — see the note at the
   * top of that file for why a testimony does not get translated.
   */
  proof: {
    eyebrow: 'In their words',
    title: 'What customers actually said',
    lead:
      'Real quotes from real orders, unedited. Filter by the service you are thinking '
      + 'about — the coaching ones in particular are worth reading before you book.',
    all: 'Everything',
    trading: 'Coins',
    boosting: 'Champs & Rivals',
    coaching: 'Coaching',
    countOf: (shown: number, total: number): string => `${shown} of ${total}`,
    // Paging. The range, not just a page number: "4-6 of 24" tells a reader how much
    // is left, where "page 2 of 8" makes them do the arithmetic.
    range: (from: number, to: number, total: number): string => `${from}–${to} of ${total}`,
    prev: 'Previous',
    next: 'Next',
    pageOf: (page: number, pages: number): string => `Page ${page} of ${pages}`,
    disclosure:
      'Every quote on this page was written by a customer and is published as they '
      + 'wrote it. Results describe those customers’ own experience — coaching outcomes '
      + 'depend on the player, and nothing here is a guarantee of a rank or a win count.',
    translated: 'Translated from English',
    originalLabel: 'The customer’s own words',
    showOriginal: 'Show original',
    showTranslation: 'Show translation',
  },

  /*
   * Interpolated strings are functions, not templates with placeholders.
   *
   * "Earn 20 points per ₹2,000" and "Gana 20 puntos por cada 2.000 ₹" put the number
   * and the symbol in different places, and a `{n}` placeholder forces every locale
   * into English word order. A function lets each language build its own sentence,
   * and the compiler still checks the arguments.
   */
  home: {
    seoTitle: (season: string) => `Safe Trading Service for EA ${season}`,
    seoDescription: (season: string) =>
      `Grow your EA ${season} club without the grind. Our traders work the transfer ` +
      `market on your account — you keep the coins. Delivery in under an hour, 100% Safety Policy.`,

    promo: { label: 'Offers and announcements' },
    hero: {
      slideDiscount: 'Discount code GFS — 10% off',
      slideBoosting: 'Champs, Rivals and Objectives boosting',
      slideSocial: 'Follow Global FUT Services',
      liveNow: 'Traders online now',
      scroll: 'Scroll',
      ratesEyebrow: 'Live rates',
      titleLead: 'The safest, fastest, most reliable place to buy',
      // The season is no longer named in the headline — it dates the line, and the
      // eyebrow and price card both carry it anyway. The argument stays in the
      // signature because every locale is typed against this one and a caller
      // already passes it.
      titleAccent: (_season: string) => 'FC coins, boosting & coaching',
      /*
       * The claim, then the evidence.
       *
       * "Safest, fastest, most reliable" is three adjectives, and adjectives are what
       * every competitor also writes. What makes them land is the row underneath:
       * each one is answered by something a reader can check on this page — the hour
       * is on the order flow, the guarantee comes from the live policy rather than
       * being typed here, and the tax line is the same promise the price card makes.
       * A claim beside its proof reads as confidence; a claim on its own reads as
       * copywriting.
       */
      subLine: 'A solution for every FC problem.',
      trustSpeed: 'Most orders in under an hour',
      trustGuarantee: () => '100% Safety Policy',
      trustTax: 'EA tax already in the price',
      body:
        'Our traders work the transfer market on your behalf — finding undervalued cards, ' +
        'flipping them, and leaving the profit in your club. You pay for the work, not for ' +
        'coins. Most orders land in under an hour.',
      startOrder: 'Start an order',
      /*
       * No count. It was read from the data so it could never go stale, which was the
       * right instinct — but printing a number invites the reader to weigh it, and
       * twenty-four reads as small beside a shop describing itself as the most
       * reliable place to buy. The half that earns the click is the second one:
       * "in their own words" is a claim this site can keep and most competitors
       * cannot, because every quote is real, translations are labelled as
       * translations, and the original is one click away. There are still no stars
       * and no faces, because none were supplied.
       */
      reviewsLink: 'What customers said, in their own words',
      reviewsLinkNote: 'Real orders · unedited',
      seeBoosting: 'Champs & Rivals boosting',
      statDeliveryValue: '< 60 min',
      statDeliveryLabel: 'Typical delivery',
      statShiftValue: '24 / 7',
      statShiftLabel: 'Traders on shift',
      statGuaranteeValue: '100% Safety Policy',
      statGuaranteeLabel: 'Every order covered',
      cardTitle: 'Safe Trading Service',
      cardSubtitle: (season: string) => `Live rates · ${season}`,
      popular: 'Popular',
      perMillion: '/ million',
      ratesUpdating: 'Rates are being updated — check back in a moment.',
      orderFrom: (price: string) => `Order from ${price} / million`,
      seePrices: 'See live prices',
      taxIncluded: 'Prices include the EA market tax. Nothing is added at the last step.',
    },

    trust: {
      delivery: 'Fastest delivery',
      encrypted: 'Your sign-in is encrypted and deleted',
      payments: 'UPI, cards and net banking',
      guarantee: 'Comes with 100% Safety Policy',
      humans: 'Real traders, not bots',
    },

    services: {
      eyebrow: (season: string) => `What we do for ${season}`,
      title: 'Get your club to the top',
      lead:
        'Pick the one that fits. Everything is priced up front, and everything is covered ' +
        'by the same guarantee.',
      tradingTag: 'Most ordered',
      tradingTitle: 'Safe Trading Service',
      tradingBody:
        'We trade the market on your account and the profit stays in your club. Priced per ' +
        'million, delivered in under an hour.',
      tradingCta: 'Start an order',
      boostTag: 'Weekend ready',
      boostTitle: 'Champs & Rivals',
      boostBody:
        'Wins pushed by players who do this every week. Pick your target, we handle the rest ' +
        'before rewards drop.',
      boostCta: 'See tiers',
      coachTag: 'Now booking',
      coachTitle: 'FUT Classes',
      coachBody:
        'One hour, one to one, with a coach who plays at the level you are chasing. ' +
        'Take a single session or a block of six.',
      coachCta: 'Book a session',
      /* Shown when the choice is asked for rather than assumed — see ServicePicker. */
      pickTitle: 'What are you here for?',
      pickLead:
        'Three different jobs, three different pages. Pick the one that matches what you '
        + 'want and we will take you straight there.',
    },

    why: {
      eyebrow: 'Why people stay',
      title: 'The six things you were about to ask',
      lead:
        'Straight answers, because everyone who buys coins has the same six worries and ' +
        'most sites answer none of them.',
      safeTitle: 'Safe',
      safeBody: () =>
        'Every order is covered by our 100% Safety Policy after delivery. If anything ' +
        'happens to your account in that window, we make it right.',
      simpleTitle: 'Simple',
      simpleBody:
        'Choose an amount, pay, and tell us where to send it. Most customers are done in ' +
        'two minutes.',
      fastTitle: 'Fast',
      fastBody:
        'Orders of any size normally land inside an hour. Our published window is longer on ' +
        'purpose — we would rather beat it than argue about it.',
      alwaysTitle: 'Always on',
      alwaysBody:
        'Someone is on shift around the clock, including the hours that matter for weekend ' +
        'league.',
      privateTitle: 'Private',
      privateBody:
        'If an order needs your sign-in, it is encrypted before it is stored and destroyed ' +
        'the moment the order is done. We tell you when to rotate your password.',
      rewardTitle: 'Rewarding',
      rewardBody: (points: number, spend: string, value: string) =>
        `Earn ${points} points for every ${spend} you spend, and climb six tiers that take ` +
        `a permanent slice off every order. Points are worth ${value} each at checkout.`,
      rewardBodyFallback: 'Earn points on every order and spend them on the next one.',
      // The featured card pulls the numbers out of the sentence. A rate you can read at a
      // glance is the argument; the same rate inside a paragraph is a claim to be skimmed.
      rewardStatEarn: 'points earned',
      rewardStatPer: (spend: string) => `per ${spend} spent`,
      rewardStatTiers: 'loyalty tiers',
      rewardStatTiersNote: 'a permanent discount at each one',
      rewardStatValue: 'per point',
      rewardStatValueNote: 'spendable at checkout',
      rewardCta: 'See how rewards work',
    },

    how: {
      eyebrow: 'How it works',
      title: 'You are buying the trading, not the coins',
      lead:
        'It matters, and not only legally. We never sell you in-game currency — those assets ' +
        'belong to EA. What you are paying for is people who know the market working it on ' +
        'your behalf.',
      step1Title: 'Tell us the amount',
      step1Body:
        'Pick your platform and how many coins you want moved. The price you see is the price ' +
        'you pay — the EA market tax and processing are already in it.',
      step2Title: 'We work the market',
      step2Body:
        'Our traders find cards listed below what they are worth, buy them, and sell them on. ' +
        'That is the service you are paying for.',
      step3Title: 'The profit stays with you',
      step3Body:
        'The coins end up in your club. You get an email the moment it is done, and the ' +
        '100% Safety Policy from that point.',
    },

    rewards: {
      badge: 'Rewards',
      title: 'Every order pays into the next one',
      body: (earn: string, value: string, cap: string) =>
        `Earn ${earn} you spend. Points are worth ${value} each and come off your next order ` +
        `automatically — up to ${cap} of it. They also move you up six tiers, and a tier ` +
        `discount never expires.`,
      howItWorks: 'How rewards work',
      createAccount: 'Create an account',

      /*
       * The value proposition, said in the reader's terms rather than the scheme's.
       *
       * Three claims, and every figure behind them is read from live policy rather
       * than written here: what you get back, that it never expires, and that it
       * costs nothing to start. Nothing in this block asserts a number of its own.
       */
      pitchTitle: 'The longer you buy here, the less you pay',
      pitchOne: 'Points off every order',
      pitchOneBody: (value: string, cap: string) =>
        `Each point is ${value} at checkout, and up to ${cap} of an order can be paid ` +
        `with them. No code to remember — the discount is already applied.`,
      pitchTwo: 'A tier discount that never expires',
      pitchTwoBody: (top: string) =>
        `Lifetime spend moves you up the ladder and nothing moves you back down. ` +
        `Spending your points does not cost you a tier. The top tier is ${top}.`,
      pitchThree: 'Free to join, earning from order one',
      pitchThreeBody: (daily: number) =>
        `An account is all it takes, and it earns from the first order rather than ` +
        `after some qualifying spend. Checking in adds ${daily} points a day.`,
      ladderTitle: 'Six tiers',
      ladderOff: (pct: string) => `${pct} off`,
      guestNote: 'Guest orders cannot earn or store points.',
      exampleTitle: 'Worked example',
      youSpend: 'You spend',
      youEarn: 'You earn',
      worthAtCheckout: 'Worth at checkout',
      pointsLand:
        'Points land once your guarantee window closes, so a refunded order never leaves ' +
        'you chasing points that were taken back.',
      pointsUnit: (points: number) => `${points} points`,
    },

    /*
     * The scrolling rail under the hero.
     *
     * Written as a list rather than as one string because the rail duplicates its
     * contents to loop seamlessly, and a pre-joined sentence cannot be split back
     * apart at the separator without guessing at punctuation in three languages.
     */
    rail: {
      items: [
        'Fastest delivery',
        'Traders on shift 24/7',
        'Sign-in encrypted, then destroyed',
        'UPI · Cards · Net Banking · Crypto',
        'Comes with 100% Safety Policy',
        '5% EA Tax Covered',
      ],
    },

    proof: {
      eyebrow: 'Operating standard',
      title: 'The numbers we are held to',
      lead:
        'Not marketing figures. These are the commitments the guarantee is actually written ' +
        'against, which is why they are conservative.',
      deliveryValue: '10–60+',
      deliveryUnit: 'min',
      deliveryLabel: 'Typical delivery',
      deliveryNote: 'The published window is longer on purpose.',
      shiftValue: '24',
      shiftUnit: 'hours a day',
      shiftLabel: 'On shift, every day',
      shiftNote: 'Including the hours weekend league runs.',
      guaranteeValue: '100%',
      guaranteeUnit: 'cover',
      guaranteeLabel: '100% Safety Policy',
      guaranteeNote: 'Full refund or a replacement account — your choice.',
      tiersValue: '6',
      tiersUnit: 'tiers',
      tiersLabel: 'Loyalty tiers to climb',
      tiersNote: 'A tier discount never expires.',
    },

    coach: {
      eyebrow: 'One to one',
      title: 'Get better in FC',
      body:
        'One hour, live, with a coach who plays at the level you are chasing. They watch ' +
        'how you actually play — not how you think you play — and fix the one habit that is ' +
        'costing you the most games.',
      point1: 'Your own footage, paused and picked apart',
      point2: 'Custom tactics built around how you defend',
      point3: 'Squad decisions that survive contact with the meta',
      point4: 'A single thing to drill before the next session',
      cta: 'Book a session',
      secondary: 'How coaching works',
      durationLabel: 'Length',
      durationValue: (m: number) =>
        m % 60 === 0 ? `${m / 60} hour${m === 60 ? '' : 's'}` : `${m} min`,
      durationNote: 'Single session, live',
      formatLabel: 'Format',
      formatValue: 'One to one',
      formatNote: 'Never a group call',
      validityLabel: 'Validity',
      validityValue: '1 month',
      validityNote: 'From the day you buy',
    },

    ask: {
      eyebrow: 'Before you buy',
      title: 'Ask anything. It answers instantly.',
      body:
        'Pricing, safety, delivery times, what happens to your sign-in — it is all in the chat ' +
        'in the corner, answered from the same policy the site runs on. If it cannot help, it ' +
        'hands you to a person.',
      cta: 'Open the chat',
    },

    closing: {
      eyebrow: 'Ready when you are',
      title: 'Your club is one order away',
      body:
        'Pick an amount, pay however you like, and get back to playing. If anything goes ' +
        'sideways, we are on the other end of the chat.',
      startOrder: 'Start an order',
      readFaqs: 'Read the FAQs',
    },
  },

  comingSoon: {
    badge: 'Coming soon',
    body:
      'We are building this properly rather than shipping half of it. Coins, boosting and ' +
      'coaching are live today and carry the same guarantee — start there, and we will let ' +
      'you know the moment this opens.',
    buyCoins: 'Buy coins',
    seeBoosting: 'See boosting',
    seoDescription: (service: string, season: string) =>
      `${service} for EA ${season} — launching shortly.`,
  },

  notFound: {
    title: 'Not found',
    heading: 'That page does not exist',
    body: 'If you followed a link from an order email, try tracking the order instead.',
    home: 'Home',
    track: 'Track an order',
  },

  boosting: {
    seoTitle: (season: string) => `${season} Champs & Rivals boosting`,
    seoDescription: (season: string) =>
      `Champs win pushes and Rivals division climbs for EA ${season}, run by players who ` +
      `do it every week.`,
    eyebrow: (season: string) => `${season} · Boosting`,
    title: 'Let someone who plays at that level take the wheel',
    lead:
      'Champs wins and Rivals climbs, handled before rewards drop. Same guarantee, same ' +
      'encryption, same people.',
    tabChamps: 'Champs wins',
    tabRivals: 'Rivals divisions',
    bestValue: 'Best value',
    wins: 'wins',
    successRateLabel: (pct: string) => `${pct} success rate`,
    successRateNote:
      'Figures published by GFS, covering orders where the account met the requirements.',
    successHeadline: (range: string, pct: string) =>
    `${range} reached in ${pct} of eligible orders.`,
    choose: 'Choose',
    tiersUpdating: 'Tiers for this service are being updated.',
    knowEyebrow: 'What you should know',
    knowTitle: 'The honest version',
    signInTitle: 'It needs your sign-in',
    signInBody:
      'Someone has to actually play the games, so boosting is always a comfort trade. Your ' +
      'details are encrypted before storage, opened only by the player working your order, ' +
      'and destroyed when it is done.',
    timingTitle: 'Timing matters',
    timingBody:
      "Champs and Rivals both run on EA's weekly clock. Order early in the window and there " +
      'is room to work; order on the last evening and there may not be.',
    discordTitle: 'Talk to us on Discord',
    discordBody:
      'If a run falls short of the rank you ordered, the price difference is credited back ' +
        'to you — usable on another order or refundable. Claims and questions go through our ' +
        'official Discord.',
    coveredTitle: 'You are covered',
    coveredBody: (cash: number, credit: number) =>
      `Our 100% Safety Policy applies. If EA acts against the account inside that ` +
      `window, you get ${cash}% back in cash or ${credit}% as store credit — your choice.`,
  },


  track: {
    seoTitle: 'Track your order',
    eyebrow: 'Order status',
    title: 'Where is my order?',
    lead:
      'Your reference and the email you used. Both, because a reference on its own turns up ' +
      'in screenshots and support chats.',
    reference: 'Order reference',
    email: 'Email',
    find: 'Find my order',
    emptyHint:
      'Your reference is in the confirmation email — it looks like GFS-26 followed by eight ' +
      'characters.',
    total: 'Total',
    placed: 'Placed',
    deliveryMethod: 'Delivery method',
    breakdown: 'Breakdown',
    history: 'History',
    payTitle: 'Waiting for payment',
    payBody:
      'This order has not been paid yet. If you closed the payment window, start again from ' +
      'the order page and your reference will be reused.',
    // -- live fulfilment ----------------------------------------------------
    progressTitle: 'Coins delivered',
    progressOf: (done: string, total: string) => `${done} of ${total}`,
    // Each of these is a stall the customer can usually clear in under a minute. The
    // supplier names the cause; these say what to do about it.
    action: {
      RESUBMIT_SIGN_IN: 'Your EA sign-in was not accepted. Submit it again below and we will pick straight back up.',
      NEW_BACKUP_CODES: 'Those backup codes have been used or are wrong. Generate fresh ones in your EA account, then submit them below.',
      SIGN_OUT_CONSOLE: 'Sign out of EA on your console, the web app and the companion app. We will retry automatically.',
      CLEAR_UNASSIGNED_ITEMS: 'You have too many unassigned items. Clear them below 50 and we will retry automatically.',
      FREE_TRANSFER_SLOTS: 'Your transfer list is full. Free at least three slots in both the list and your targets.',
      ADD_COINS: 'Your club needs at least 5,000 coins on it before we can start.',
      SOLVE_CAPTCHA: 'EA is asking you to solve a captcha. Sign in on the web app once, complete it, then sign out.',
      FIX_PERSONA: 'The wrong EA persona is selected. Switch to the one holding your club.',
      ACCOUNT_UNUSABLE: 'This account cannot be used — it has no transfer market access or no club. Get in touch and we will refund you.',
      BANNED: 'EA has restricted this account. Get in touch: this is what the guarantee is for.',
      SUPPLIER_SIDE: 'This one is on us, not you. We are on it and will update you here.',
    },
    credentialsTitle: 'We need your sign-in to start',
    credentialsBody:
      'Sign in to your account to submit it securely. It is encrypted before storage and ' +
      'deleted when the order is done.',

    // -- the sign-in form itself -------------------------------------------
    // Wording matters more than usual here. The customer is being asked for the
    // credential they have been told their whole life not to share, so every line
    // says what happens to it rather than asking them to trust a promise.
    credFormTitle: 'Submit your EA sign-in',
    credFormLead:
      'Encrypted with a key unique to this order before it is written down, opened only by ' +
      'the trader fulfilling it, and destroyed the moment the order completes.',
    credFormRetention: (days: string) =>
      `Deleted automatically after ${days} even if something goes wrong on our side.`,
    howToFind: 'How to find?',
    credEmail: 'EA account email',
    credEmailHint: 'The address you sign in to EA with, not your delivery address.',
    credPassword: 'EA password',
    credPasswordHint: 'Change it as soon as the order is delivered — we will remind you.',
    credShow: 'Show password',
    credHide: 'Hide password',
    credBackupCodeN: (n: number) => `Backup code ${n}`,
    credReassureLead: 'Your account details are encrypted, opened only when needed to complete your order, and deleted immediately after — see our',
    credReassureLink: 'Terms of Service',
    credReassureTail: 'for details.',
    credBackupCodes: 'Backup codes',
    credBackupCodesFind: 'How to find backup codes',
    credBackupCodesHint: 'One per line. Needed if your account asks for a code at sign-in.',
    credHandle: 'Console gamertag or PSN ID',
    credHandleHint: 'Optional — helps the trader confirm they are on the right account.',
    credNote: 'Anything else we should know?',
    credAckSignedOut:
      'I am signed out of my account on console, the web app and the companion app.',
    credAckMarket: 'My transfer market is unlocked.',
    credAckItems: 'I have fewer than five unassigned items.',
    credAckTerms: 'I understand these details are stored encrypted and deleted on completion.',
    credSubmit: 'Submit securely',
    credSubmitting: 'Encrypting…',
    credDone: 'Your sign-in is with us',
    credDoneBody:
      'Nothing more to do. It is encrypted, and it is deleted the moment this order is ' +
      'marked complete.',
    credSignInFirst: 'Sign in to submit it',
    credError: 'That could not be submitted. Please check the fields and try again.',
    stuckTitle: 'We are stuck on something',
    stuckBody:
      'Usually this means the account was online, the transfer market was locked, or there ' +
      'were too many unassigned items. Get in touch and we will sort it out.',
    deliveredTitle: 'Delivered — two things to do',
    deliveredBody: (until: string) =>
      `Change your EA password and regenerate your backup codes. Everything you gave us has ` +
      `already been destroyed. Your guarantee runs until ${until}.`,
    reviewTitle: 'Under review',
    reviewBody: 'We are looking into your guarantee claim and will be in touch.',
  },

  auth: {
      continueGoogle: 'Continue with Google',
      continueDiscord: 'Continue with Discord',
      orDivider: 'or',
      oauthNoEmail: 'That account did not share an email address, so we could not sign you in. Use your email and password instead.',
      oauthUnverified: 'An account already exists for that email. Sign in with your password once, and you can link the two afterwards.',
      oauthFailed: 'That sign-in did not complete. Please try again, or use your email and password.',
    signInTitle: 'Sign in',
    registerTitle: 'Create an account',
    signInHeading: 'Welcome back',
    registerHeading: 'Create your account',
    signInLead: 'Sign in to see your orders and points.',
    registerLead: 'Earn points on every order and keep your history in one place.',
    email: 'Email',
    password: 'Password',
    passwordHint:
      'At least 12 characters. A short phrase works well and is easier to remember than a ' +
      'mangled word.',
    displayName: 'Display name',
    acceptPrefix: 'I accept the',
    acceptTerms: 'terms of service',
    acceptAnd: 'and',
    acceptPrivacy: 'privacy policy',
    createAccount: 'Create account',
    signInButton: 'Sign in',
    haveAccount: 'Already have an account?',
    newHere: 'New here?',
    signInLink: 'Sign in',
    createLink: 'Create one',
    guestNote:
      'You do not need an account to order — guest checkout works fine. An account is how ' +
      'you collect points.',
    genericError: 'Something went wrong. Please try again.',
  },

  support: {
    seoTitle: 'Support',
    seoDescription: 'Get in touch about an order.',
    eyebrow: 'Support',
    title: 'Tell us what is happening',
    lead:
      'Someone is on shift around the clock. Include your order reference if you have one ' +
      'and we will find it straight away.',
    yourEmail: 'Your email',
    orderRef: 'Order reference',
    orderRefHint: 'Optional, but it speeds things up.',
    subject: 'Subject',
    message: 'What is going on?',
    noPassword: 'I have not included my password or backup codes in this message.',
    noPasswordNote:
      'We will never ask for them here. If an order needs your sign-in, it is collected ' +
      'through the encrypted form on the order itself.',
    send: 'Send message',
    sendFailed: 'Could not send that.',
    fasterTitle: 'Faster answers',
    faster1: 'Order not moving? Check it is signed out everywhere first.',
    faster2: 'Wrong amount? Send us the reference and a screenshot.',
    faster3: 'Ban or coin removal? Include the date it happened.',
    neverTitle: 'What we will never do',
    neverBody:
      'Ask for your password over chat or email, ask you to move payment off the site, or ' +
      'contact you first about a "problem" with your order. If someone does, it is not us.',
  },

  account: {
    seoTitle: 'My account',
    greeting: (name: string) => `Hello, ${name}`,
    fallbackTitle: 'Your account',
    yourOrders: 'Your orders',
    noOrders: 'No orders yet',
    noOrdersBody: 'When you place one it will appear here, with its full history.',
    statement: 'Points statement',
    rewardPoints: 'Reward points',
    worth: 'worth',
    atCheckout: 'at checkout',
    earnedTotal: (total: string, cap: number) =>
      `Earned ${total} in total. Points can cover up to ${cap}% of an order and land once ` +
      `each guarantee window closes.`,
    coaching: 'Coaching',
    sessionsLeftOne: 'session left to book',
    sessionsLeftMany: 'sessions left to book',
    useThemBy: (date: string) => `Use them by ${date}.`,
    bookSession: 'Book a session',
    manageSessions: 'Manage sessions',
    quickActions: 'Quick actions',
    startOrder: 'Start an order',
    needChange: 'Need something changed on a live order?',
    contactSupport: 'Contact support',
    withReference: 'with the reference.',
  },

  order: {
    seoTitle: (season: string) => `Buy ${season} coins`,
    seoDescription: 'Choose your platform and amount. Live pricing, no hidden fees at checkout.',
    pricesUnavailable: 'Prices are unavailable',
    title: 'Build your order',
    lead: 'Everything is priced up front. Nothing is added at the last step.',
    /*
     * The number is carried by the ring beside the heading, not by the words.
     *
     * These read "1 · Platform" while a marker to their left already said 01 — the
     * step number twice, half a centimetre apart. The argument is kept in the
     * signature because the call sites pass it and because a locale may want it back;
     * it is simply not spent on repeating what the eye has already been told.
     */
    stepPackage: (_n: number, service: string) => service,
    stepPlatform: (_n: number) => 'Platform',
    stepAmount: (_n: number) => 'Amount',
    stepDiscounts: (_n: number) => 'Discounts',
    perMillion: '/ million',
    // The trading proposition, in the three places a price appears. Kept short: it sits
    // under a per-million figure and beside a total, not in running text.
    taxIncludedShort: 'Included',
    taxIncludedInline: 'EA 5% tax included',
    taxIncludedTitle: 'EA’s 5% tax is on us',
    taxIncludedBody:
      'EA takes 5% of every transfer on the market. Most sellers add it to your bill at ' +
      'checkout. We do not — the price you see per million is the price you pay.',
    amountAria: 'Coin amount in millions',

    /*
     * The typed amount, expressed in thousands.
     *
     * The slider works in millions because that is the unit the rate card prices in,
     * but nobody buying coins thinks "0.11M" -- they think 110K. The field converts,
     * so the customer types the number they already had in their head.
     */
    savingsTitle: 'Discounts and rewards',
    savingsEmpty: 'No discount applied yet — a coupon or points would show here.',
    youSave: 'You save',
    amountManualLabel: 'Or type an exact amount',
    amountManualHint: (stepK: number, minK: string, maxK: string) =>
      `In thousands, ${stepK}K at a time. Anything between ${minK} and ${maxK}.`,
    amountManualUnit: 'K',
    amountSnapped: (shown: string) => `Rounded to the nearest step — ${shown}.`,

    /* -------------------------------------------------- requirements (note 12) --- */
    requirementsTitle: 'Requirements to order',
    requirementsLead:
      'Four things have to be true before we can move coins. Worth checking now — each ' +
      'one is an order that otherwise stalls after you have paid.',
    reqCompanion: 'Web or Companion App access',
    reqCompanionNote: 'This is how the trade is done. No access, no transfer.',
    reqMarket: 'Transfer Market unlocked',
    reqMarketNote: 'A new or recently reset account is often still locked.',
    reqMinCoins: 'At least 5,000 coins in the club',
    reqMinCoinsNote: 'Listing a card needs a starting balance.',
    reqUnassigned: 'Fewer than 5 unassigned items',
    reqUnassignedNote: 'A full unassigned pile blocks transfers outright.',

    /* ----------------------------------------------- delivery method (note 13) --- */
    deliveryFixed: 'GFS Trading Method 3.0 (Latest)',
    deliveryFixedHint:
      'Our current method, used for every coin order. There is nothing to choose.',

    /*
     * The season's volume, as a reading rather than a sentence.
     *
     * Shown only on the trading SKU. `/order` is the same page for boosting and
     * coaching, and a coin count in the masthead of a coaching order would be
     * measuring the wrong thing.
     *
     * <b>"Transferred" rather than "sold", deliberately.</b> This service moves
     * coins on the transfer market into a club the customer already owns; it does
     * not sell them. The hero says "you pay for the work, not for coins", the
     * trading explainer is titled "You are buying the trading, not the coins", and
     * the terms are written the same way. A masthead advertising coins for sale
     * would contradict every other line on the page. The FAQ already uses
     * "transferred" for the same reason.
     */
    volumeLabel: 'Coins transferred',
    volumeValue: '5.5B+',
    volumeNote: (season: string) => `Across client orders in ${season}.`,
    tabDiscount: 'Discount',
    tabRewards: 'Rewards',
    rewardsNoAccount: 'Sign in to spend reward points on this order.',
    rewardsNoneYet: 'You have no points to spend yet.',
    rewardsCapNote: (cap: string) => `Up to ${cap} of an order can be paid in points.`,
    rewardsUseAll: 'Use max',
    rewardsClear: 'Clear',
    and: 'and',
    consentLead: 'I understand and agree to the',
    consentTerms: 'Terms of Service',
    consentPrivacy: 'Privacy Policy',
    consentAml: 'AML & KYC Policy',
    howToFind: 'How to find?',
    couponLabel: 'Coupon code',
    couponApply: 'Apply',
    couponHint: 'Have a discount code? Enter it here.',
    couponApplied: (code: string) => `${code} applied.`,
    pointsLabel: 'Reward points',
    pointsHintUsable: (balance: number, usable: number) =>
      `You have ${balance}. Up to ${usable} usable here.`,
    pointsHintPlain: (balance: number) => `You have ${balance} points.`,
    createAccount: 'Create an account',
    createAccountRest: 'to earn points on this order and spend them on the next.',

    summaryTitle: 'Your order',
    total: 'Total',
    /*
     * Two lines, because a guest earns nothing.
     *
     * `pointsEarnedOn` is computed from the total and the currency alone -- it has no
     * idea whether an account is attached -- so the quote returns a real number for a
     * guest too. Showing it unqualified told a guest they were earning points on an
     * order the terms of service say cannot earn any. The number is right; who it
     * belongs to is the part the quote cannot know.
     *
     * "About" and "once your guarantee window closes" are both load-bearing: points are
     * granted when the order completes and the safety window has passed, not at
     * checkout, and a customer who reads this as an instant balance will ask where they
     * went.
     */
    earnsPoints: (points: number) =>
      `You'll earn about ${points} reward points, once your guarantee window closes.`,
    earnsPointsGuest: (points: number) =>
      `Sign in to earn ${points} reward points on this order — guest orders cannot earn them.`,
    continue: 'Continue',
    priceNote:
      'Prices are held for a few minutes and re-checked when you pay, so what you see here ' +
      'is what is charged.',
    refreshingPrice: 'Refreshing price…',
    priceHeld: (time: string) => `Price held for ${time}`,

    seoTitleFor: (service: string, season: string) => `Order ${service} — EA ${season}`,
    seoDescriptionGeneric: 'Everything priced up front. Nothing added at the last step.',
    cartTitle: 'Your cart',
    cartRemove: 'Remove from cart',
    continueShopping: 'Continue shopping',
    deliveryInfoTitle: 'Delivery information',
    deliveryInfoLead:
      'For this type of delivery, the trader signs in to your account and hands over the '
      + 'FC 26 coins, players, packs or SBCs.',
    deliveryInfoPacks:
      'If you buy packs, we transfer the matching number of coins to your account and you '
      + 'open the packs yourself.',
    deliveryInfoWait:
      'There is nothing else for you to do but wait until the transfer lands.',
    deliveryCheck1:
      'Transfer market access must already be unlocked on the FUT Web or Companion App.',
    deliveryCheck2:
      'Do not sign in to the account while the service is running.',
    deliveryCheck3:
      'The transfer list must be empty, and the account needs at least 5,000 coins.',
    deliveryCheck4: 'Have your backup codes to hand.',
    clickHere: 'Click here',
    backupCodePlaceholder: 'Each code is 8 digits',
    eaEmailPlaceholder: 'EA Web/Companion App',
    fixFieldsError: 'Please check the highlighted fields.',
    errEaEmail: 'Please enter Origin (Web App) Email!',
    errEaPassword: 'Please enter Origin (Web App) Password!',
    errBackupCode: (n: number) => `Please enter Backup Code ${n}!`,
    errBackupCodeFormat: (n: number) => `Backup Code ${n} must be exactly 8 digits.`,
    userInfoTitle: 'Your details',
    fullName: 'Full name',
    fullNameHint: 'The name we put on your receipt.',
    countryCode: 'Country code',
    phoneHelper: 'Only used for urgent order issues — we will never spam you.',
    discordLabel: 'Discord (optional)',
    discordHelper: 'We may reach out on Discord for faster order support.',
    discordPlaceholder: 'yourname',
    whereTitle: 'Where should it go?',
    email: 'Email',
    emailHint: 'Your receipt and delivery notice go here.',
    phone: 'Phone',
    phoneHint: 'Optional — only if we get stuck.',
    eaName: 'EA account name',
    eaNameHint: 'So the trader can find you.',
    eaNamePlaceholder: 'Gamertag or EA ID',
    deliveryMethod: 'Delivery method',
    deliveryAuctionHint:
      'You list a card and we buy it off the market. Where a sign-in is needed it is '
      + 'encrypted and destroyed when the order is done.',
    deliveryComfortHint:
      'We sign in and trade directly. Faster for large amounts, and we ask for your ' +
      'sign-in at checkout.',
    deliveryAuction: 'Transfer market — you list, we buy',
    deliveryComfort: 'Comfort trade — we sign in for you',
    coachingNextTitle: 'What happens next',
    coachingNextBody:
      'Your sessions land in your account as soon as payment clears, and you book them from ' +
      'the coaching page whenever suits you. We never need your EA sign-in for coaching — ' +
      'you play your own account while your coach watches.',
    signInTitle: 'About your sign-in',
    signInBody:
      'It is encrypted the moment it reaches us, only ever opened by the trader working ' +
      'your order, and destroyed within 24 hours whether the order completes or not. We ' +
      'will remind you to change your password afterwards.',
    noteLabel: 'Anything we should know?',
    notePlaceholder: 'Optional',
    beforeYouPay: 'Before you pay',
    readyCheck:
      'My transfer market is unlocked, I have at least 5,000 coins and fewer than five ' +
      'unassigned items, and I am signed out on console, web app and companion app.',
    termsPrefix: 'I have read the',
    termsLink: 'terms of service',
    termsCoaching: 'and understand I am buying a coaching service.',
    termsTrading: 'and understand I am buying a trading service, not in-game currency.',
    back: 'Back',
    pay: (amount: string) => `Pay ${amount}`,
    acceptTermsError: 'Please accept the terms to place your order.',
    readyChecksError: 'Please confirm your account is ready — it saves both of us a delay.',

    orderCreated: 'Order created',
    keepReference: 'Keep that reference — it is how you track this order and how support finds it.',
    stubTitle: 'Payment gateway is not configured',
    stubBody:
      'This environment is running without live payment credentials, so no money will move. ' +
      'The order exists and is visible in the operations console. Set the Razorpay keys to ' +
      'enable real payments.',
    payWindowFailed: 'The payment window would not open. Check your connection and try again.',
    trackOrder: 'Track this order',

    /* ---------------------------------------------------------- manual payment --- */
    payTitle: 'Pay for your order',
    payIntro:
      'Pay with any of the options below, then enter the reference your payment app gives you. ' +
      'We check it against our account and start your order once it lands.',
    payTabUpi: 'UPI',
    payTabPaypal: 'PayPal',
    payTabCrypto: 'Crypto',
    payScanHint: 'Scan the code with your payment app, or copy the address below.',
    payAmountDue: (total: string) => `Amount to send: ${total}`,
    payPayTo: 'Paying',
    payCopy: 'Copy',
    payCopied: 'Copied',
    payCopyFailed: 'Could not copy — select the address and copy it by hand.',
    payOpenPaypal: 'Open PayPal',
    /*
     * Deliberately blunt, and deliberately not softened.
     *
     * Sending the wrong asset, or the right asset on the wrong chain, destroys it. There
     * is no support process that recovers it and no refund we can make. A warning that
     * reads as boilerplate gets skimmed, so this one names the loss.
     */
    payCryptoWarning:
      'Only send USDT on the TRON (TRC20) network to this address. Sending any other asset, ' +
      'or using a different network, will result in permanent loss of funds.',
    payReferenceLabel: 'UTR / Transaction reference number',
    payReferenceHint:
      'After completing your payment, enter the reference number here so we can verify and ' +
      'process your order.',
    // Return type pinned to string. Left to inference it becomes a union of these three
    // literals, which the Spanish and French files then cannot satisfy.
    payReferencePlaceholder: (kind: string): string => {
      if (kind === 'UTR') return '12-digit UTR number'
      if (kind === 'transaction hash') return 'Transaction hash (TXID)'
      return 'Transaction ID'
    },
    payReferenceRequired: 'Enter the reference number from your payment before submitting.',
    paySubmit: 'I have paid — submit reference',
    payChangeMethod: 'Pay a different way',
    /*
     * The confirmation says "checking", never "paid". At this point a customer has typed a
     * string and nothing has been verified; a green tick reading "payment received" would
     * be telling them something we do not know.
     */
    payClaimTitle: 'Reference received — we are checking it',
    payClaimBody: (reference: string) =>
      `We have your reference ${reference}. Someone checks this against our account and ` +
      'releases your order once the payment shows up. You will get an email when it does.',
    payClaimResubmit: 'Entered it wrong? Submit a different reference',
    payClaimFailed: 'We could not record that reference. Check it and try again.',
    payMethodsFailed:
      'We could not load the payment options. Refresh the page, or contact support with your ' +
      'order reference.',
    coachingNextStub:
      'Session credits are added when a payment settles. No payment can settle in this '
      + 'environment, so none have been added and the booking calendar will stay hidden. '
      + 'On the live site this is where you would go and book.',
    coachingNextCta: 'Go to booking',
    coachingNextCtaNow: 'Book your first session',
  },

  help: {
    seoTitle: 'Help centre',
    seoDescription: 'Answers to the questions people actually ask before ordering.',
    eyebrow: 'Help centre',
    title: 'Questions people actually ask',
    lead:
      'If the answer is not here, support is a message away and someone is on shift.',

    groupBefore: 'Before you order',
    groupOrdering: 'Ordering and delivery',
    groupMoney: 'Money and support',

    qServices: 'What services does Global FUT Services provide?',
    aServices:
      'We offer three core services to improve your FUT experience: secure and fast FUT ' +
      'coin transfers, FUT Champs boosting to help you reach the rank you want, and ' +
      'personalised FUT coaching sessions designed to lift your gameplay.',

    qSafety: 'How can I be sure my account is safe?',
    aSafety:
      'Your account’s safety is our top priority. With over four years in the industry ' +
      'and a record of two billion coins transferred and 1,400 successful boosts, we use ' +
      'secure, time-tested methods that protect your account at every step.',

    qPartners: 'Are you partnered with any trusted sources?',
    aPartners:
      'Yes. We have partnered with the YouTube channels Vibhor Sharma and FC Breakdown ' +
      'for nearly three years. Those partnerships reflect our commitment to integrity and ' +
      'a service you can trust.',

    qSpeed: 'How quickly can I expect results?',
    aSpeed:
      'Coin transfers are delivered in 10 to 30 minutes. Champs boosting runs with minimal ' +
      'wait times. Coaching sessions are scheduled at a time that works for you.',

    qOrdering: 'How do I place an order?',
    aOrdering:
      'Build your order on the site, choose your platform and amount, and pay. You will get ' +
      'an order reference straight away, and our team picks it up from there. If you would ' +
      'rather talk it through first, contact support and someone will help.',

    qCredentials: 'What do you need from me to buy or sell coins?',
    aCredentials:
      'For any coin order — both delivery methods — we need your EA login email and ' +
      'password and three EA backup ' +
      'codes, and we ask for them only after you have paid. Make sure your account has ' +
      'access to the transfer market on the EA Web App so we can process everything ' +
      'smoothly. Once your order is under way you will get a confirmation, and it is best ' +
      'not to log in until we are finished so nothing is interrupted.',

    qTax: 'What about the 5% EA tax on my coin purchase?',
    aTax:
      'EA takes a 5% cut of every transfer on the market — that is EA’s charge, not ' +
      'ours. We show it as its own line on your quote before you pay rather than burying ' +
      'it in the headline price, so the number you see is the number you are charged. ' +
      'Nothing is added afterwards.',

    qPayment: 'What payment methods do you accept?',
    aPayment:
      'UPI — including GPay, PhonePe, Paytm and CRED — plus debit and credit cards ' +
      'and net banking, all handled by Razorpay. PayPal is available on request and ' +
      'carries an extra fee. Skrill and Bitcoin are not live yet.',

    /*
     * Backup codes, as a walkthrough rather than a definition.
     *
     * This is the step of the credential form people abandon. The field asks for
     * something most players have never looked at, and the previous copy assumed they
     * knew where it lived. Written as numbered steps because that is what somebody
     * reads with the EA site open in the next tab.
     *
     * INCOMPLETE ON PURPOSE: the brief asks for screenshots at each step, and no
     * screenshot assets were supplied. Words-only is the honest version — inventing a
     * described sequence of screens that may not match EA's current UI would be worse
     * than none, and EA moves this page.
     */
    qBackupCodes: 'How do I find my backup codes?',
    aBackupCodes:
      'Backup codes come from your EA account, not from us. Sign in at your EA account '
      + 'settings, open the Security section, and look for login verification — the '
      + 'backup or recovery codes sit there, and you can generate a fresh set at any '
      + 'time. Copy the whole list and paste it in; we use one and the rest stay yours. '
      + 'Generate new ones after your order is finished and the old set stops working. '
      + 'If the section is not where this describes, EA has moved it — message us on '
      + 'Discord and we will walk you through the current screens.',

    qRefund: 'What is your refund policy?',
    aRefund:
      'Once we have started work an order cannot simply be cancelled, because the coins ' +
      'are already moving. You are not left without cover though: every order carries a ' +
      '7-day guarantee from delivery, and if a claim is upheld you choose 100% back as ' +
      'store credit or 50% back in cash. If we cannot complete an order at all — an ' +
      'account with no transfer market access, for instance — you are refunded.',

    qSupport: 'What if I have questions during the process?',
    aSupport:
      'We are here around the clock. Our 24/7 support team is always ready to answer a ' +
      'question or help with an issue — reach out at any time.',

    stillStuck: 'Still stuck?',
    stillStuckBody: 'Tell us what is happening and we will pick it up.',
    contactSupport: 'Contact support',
  },

  rewards: {
    seoTitle: 'Rewards',
    seoDescription:
      'Earn points on every order, climb six tiers, and take an automatic discount at ' +
      'checkout that never expires.',
    loading: 'Loading the rewards scheme…',
    eyebrow: 'Rewards',
    title: 'Two ways to be paid back',
    lead:
      'Every order earns points you can spend on the next one. Those same points move you up ' +
      'six tiers, and each tier takes a permanent slice off everything you order after it.',
    earningLabel: 'Earning',
    earningUnit: (points: number) => `${points} pts`,
    earningPer: (spend: string) => `for every ${spend} you spend`,
    spendingLabel: 'Spending',
    spendingPer: 'per point at checkout',
    topLabel: 'At the top',
    effectiveLabel: 'Effective',
    topBody: (tier: string) => `points back, plus a ${tier} discount on every order`,
    effectiveBody: 'back on everything you order',
    tiersTitle: 'The six tiers',
    tiersLead:
      'Tiers are earned on lifetime points — the running total of everything you have ever ' +
      'earned. Spending your balance never moves you back down, and your tier discount comes ' +
      'off automatically at checkout with nothing to enter.',
    colTier: 'Tier',
    colPoints: 'Lifetime points',
    colDiscount: 'Discount',
    fromFirstOrder: 'From your first order',
    rulesTitle: 'The rules, in full',
    rulePointsLand: 'Points land when your guarantee closes.',
    rulePointsLandBody: (days: number) =>
      `${days} days after delivery, not at payment. It means a refunded order never leaves ` +
      `you chasing points that were taken back again.`,
    ruleCap: (cap: string) => `Up to ${cap} of an order can be paid in points.`,
    ruleCapBody:
      'A ceiling keeps refunds simple and keeps the scheme from becoming something to farm.',
    ruleNoDemote: 'Spending points never demotes you.',
    ruleNoDemoteBody:
      'Your tier follows lifetime earnings, so using the reward we gave you does not cost ' +
      'you the standing you earned getting it.',
    ruleDaily: (points: number) => `${points} points a day, just for checking in.`,
    ruleDailyBody:
      'One claim per day, from your account page. They spend like any other point and they ' +
      'count toward your tier.',
    ruleNoCash: 'Points have no cash value.',
    ruleNoCashBody:
        'They are a discount on future orders, not a balance you can withdraw. Guest orders '
        + 'cannot earn or store them.',
    startedTitle: 'Getting started',
    startedBody:
      'Points need an account — a guest order has nowhere to put them. Creating one takes a ' +
      'moment and your balance is visible from the first order onwards, with a statement ' +
      'showing exactly where every point came from.',
    createAccount: 'Create an account',
    startOrder: 'Start an order',
    standingEyebrow: 'Where you stand',
    discountOffEvery: (pct: string) => `${pct} off every order`,
    lifetimeLine: (lifetime: string, balance: string) =>
      `${lifetime} lifetime points · ${balance} available to spend`,
    toNextTier: (points: string, tier: string) => `${points} more points to ${tier}.`,
    claim: (points: number) => `Claim +${points}`,
    claimedToday: (points: number) => `Today's ${points} points are claimed.`,
    comeBack: 'Come back tomorrow.',
    claimFailed: 'Could not claim today’s bonus.',
  },

  coaching: {
    seoTitle: (season: string) => `${season} FUT coaching`,
    seoDescription: (season: string) =>
      `One-to-one EA ${season} coaching: squad building, trading, and the habits that ` +
      `actually move your rank. Book a single session or a six-session block.`,
    eyebrow: (season: string) => `${season} · Coaching`,
    title: 'Get better on purpose, not by accident',
    lead:
      'One hour, one to one, on your squad and your habits. We watch you play, stop ' +
      'where it matters, and leave you with something you can repeat.',
    // Return type stated: without it the ternary infers the literal union
    // 'session' | 'sessions', and every other locale then has to use those exact
    // English words to satisfy the Dictionary type.
    creditsLeft: (n: number): string => (n === 1 ? 'session' : 'sessions'),
    creditsAlertPrefix: 'You have',
    creditsAlertSuffix: 'left. Pick a coach and a time below.',
    loadCoachesFailed: 'Could not load coaches.',
    needMore: 'Need more sessions?',
    pricingHeading: 'Coaching pricing',
    packBody:
      'Six sessions to use within a month. Book them as you go — one at a time, at whatever ' +
      'pace suits you.',
    singleBody:
      'One session. A good way to see whether this is for you before committing to a block.',
    buyBlock: 'Buy the package',
    buySession: 'Buy a session',
    saveBadge: (pct: number) => `Save ${pct}%`,
    whoTitle: 'Meet your coach',
    peak: 'Peak:',
    speaks: 'Speaks:',
    bookTitle: 'Book a session',
    noCoachesTitle: 'No coaches are taking bookings right now',
    noCoachesBody:
      'Your sessions do not expire yet — we will email you the moment someone opens up.',
    previousMonth: 'Previous month',
    nextMonth: 'Next month',
    dayAvailable: 'times available',
    dayUnavailable: 'no times',
    noSlotsInMonth: (month: string): string => `Nothing free in ${month}`,
    timesShownIn: 'Times shown in',
    coachesFrom: (name: string, zone: string) => `${name} coaches from ${zone}`,
    coachDiscord:
      'After ordering, reach your coach or GFS Support on our official Discord for scheduling ' +
      'and session communication:',
    policyLine: (minutes: number, hours: number) =>
      `${minutes} minutes · free to move up to ${hours}h before`,
    /* Both lengths on the booking screen, where a customer is about to spend a credit. */
    policyLineBoth: (single: number, block: number, hours: number) =>
      `${single} minutes single, ${block} minutes in a block · free to move up to ${hours}h before`,
    bookedFor: 'Booked for',
    emailedDetails: '. We have emailed you the details.',
    loadSlotsFailed: 'Could not load availability.',
    bookingFailed: 'That booking did not go through.',
    noSlotsTitle: 'No free times in the next two weeks',
    noSlotsBody: (name: string) =>
      `${name} is fully booked. Try another coach, or check back — cancellations free up ` +
      `slots regularly.`,
    upcomingTitle: 'Your upcoming sessions',
    alsoAppears: 'Every session also appears in',
    yourAccount: 'your account',
    withCoach: 'with',
    join: 'Join',
    cancel: 'Cancel',
    cancelRefunds: 'Cancel this session? The credit goes back to your balance.',
    cancelForfeits:
      'Cancel this session? It is inside the notice period, so the session is used up.',
    cancelFailed: 'Could not cancel that session.',
  },

  chat: {
    launcher: 'Ask a question',
    close: 'Close',
    assistant: 'FUT Assistant',
    status: 'Answers instantly',
    greeting:
      'Hi. I can answer the questions we get asked most, straight away. Pick one below, or ' +
      'talk to a person if you would rather.',
    suggestions: 'Common questions',
    more: 'More questions',
    typing: 'Typing',
    anythingElse: 'Anything else?',
    talkToHuman: 'Talk to a person',
    humanReply:
      'That one is better answered by a person — someone is on shift now and will pick it up.',
    openSupport: 'Message support',
    readFull: 'Read the full help centre',
    automated: 'Automated answers from our help centre. A person is always one click away.',
    transcriptTitle: 'The questions people actually ask',
  },

  /*
   * Site search.
   *
   * The keyword lists are not translations of one another. They are the words someone
   * in that language would actually type, plus the terms that stay English everywhere
   * -- "faq", "ps5", "xbox", "kyc" -- because a Spanish-speaking player still types
   * "ps5". A literal translation of the English list would search well in a dictionary
   * and badly on a phone.
   *
   * They are also written unaccented where the accent is optional at the keyboard
   * ("garantia", "reembolso"): the matcher strips accents from both sides, so this
   * costs nothing and documents the intent.
   */
  search: {
    open: 'Search',
    label: 'Search the site',
    placeholder: 'Search pages, answers and services\u2026',
    close: 'Close search',
    clear: 'Clear search',
    empty: 'Start typing to search pages, help answers and services.',
    noResults: (query: string) => `Nothing matches \u201c${query}\u201d.`,
    noResultsHint: 'Try a service name, a question, or an order reference.',
    results: (count: number) => (count === 1 ? '1 result' : `${count} results`),
    groupActions: 'Go to',
    groupPages: 'Pages',
    groupServices: 'Services',
    groupFaq: 'Answers',
    trackOrder: (ref: string) => `Track order ${ref}`,
    trackOrderHint: 'Open the tracker with this reference filled in',
    hintMove: 'to move',
    hintOpen: 'to open',
    hintClose: 'to close',
    pages: {
      home: { label: 'Home', keywords: 'home start front page global fut services' },
      order: { label: 'Buy coins', keywords: 'trading coins buy order purchase price cost how much checkout platform pc ps5 playstation xbox million' },
      boosting: { label: 'Boosting', keywords: 'boost champs rivals weekend league wins division rank carry price cost how much' },
      coaching: { label: 'Coaching', keywords: 'coaching coach lessons classes training session book improve tutor price cost how much hourly' },
      rewards: { label: 'Rewards', keywords: 'rewards points loyalty tiers discount earn spend wallet cashback' },
      track: { label: 'Track order', keywords: 'track status order reference delivery progress where is my order' },
      help: { label: 'Help centre', keywords: 'help faq faqs questions answers guarantee ban refund safe support' },
      support: { label: 'Support', keywords: 'support contact message email problem complaint ticket' },
      account: { label: 'My account', blurb: 'Your orders, your points balance and your details.', keywords: 'account profile my orders history points balance settings' },
      login: { label: 'Sign in', blurb: 'Sign in, or create an account to start earning points.', keywords: 'sign in log in login register create account password' },
      cards: { label: 'Player cards', blurb: 'Player cards are not open yet. Here is what is coming.', keywords: 'cards player cards icons coming soon' },
      terms: { label: 'Terms of service', blurb: 'The contract: delivery, guarantees, refunds and what is final.', keywords: 'terms legal contract conditions refund policy' },
      privacy: { label: 'Privacy policy', blurb: 'What we collect, how long we keep it, and how to have it deleted.', keywords: 'privacy legal data personal information cookies gdpr' },
      aml: { label: 'AML & KYC', blurb: 'How we verify identity and why we sometimes have to ask.', keywords: 'aml kyc legal anti money laundering identity verification' },
    },
  },
  /*
   * Server-supplied strings.
   *
   * The API builds service names, variant labels and price-breakdown lines in Java, in
   * English. These are the translations, keyed on the codes the API sends alongside
   * them — never on the English text, so a wording change on the server cannot silently
   * break a locale. Anything missing here falls back to whatever the server said.
   *
   * Rank names stay in English on purpose: "Champion II" and "Elite V" are what EA
   * shows inside the game in every locale, and a player has to be able to match what
   * they bought to what is on their console.
   */
  catalog: {
    services: {
      TRADING_SERVICE: 'Safe Trading Service',
      BOOST_CHAMPS: 'Champs Boosting',
      BOOST_RIVALS: 'Rivals Boosting',
      COACHING: 'FUT Classes',
      CARDS: 'Player Cards',
    },
    variants: {
      WINS_9: '9 wins · Champion II',
      WINS_10: '10 wins · Champion I',
      WINS_11: '11 wins · Elite V · Rank 5',
      WINS_12: '12 wins · Elite IV · Rank 4',
      WINS_13: '13 wins · Elite III · Rank 3',
      WINS_14: '14 wins · Elite II · Rank 2',
      WINS_15: '15 wins · Elite I · Rank 1',
      WINS_EXTRA_8: '+8 extra wins',
      DIV_5_TO_4: 'Division 5 to 4',
      DIV_4_TO_3: 'Division 4 to 3',
      DIV_3_TO_2: 'Division 3 to 2',
      DIV_2_TO_1: 'Division 2 to 1',
      DIV_1_TO_ELITE: 'Division 1 to Elite',
      SINGLE_SESSION: 'Single session · 1 hour',
      MONTHLY_6_SESSIONS: '6 sessions × 40 minutes',
    },
    /** Coin quantities, which are written differently per language. */
    millions: (qty: string): string => `${qty}M`,
    lines: {
      base: (service: string, detail: string): string => `${service} — ${detail}`,
      marketTax: (pct: string): string => `EA transfer market tax (${pct})`,
      gatewayFee: (pct: string): string => `Payment processing (${pct})`,
      walletRedemption: (points: number): string => `${points} reward points redeemed`,
      coupon: (code: string): string => `Coupon ${code}`,
      referral: (code: string): string => `Creator code ${code}`,
      tierDiscount: 'Member discount',
    },
  },
}/*
 * Deliberately not `as const`. That would infer every value as its exact literal, and
 * a locale file typed against it could then only contain the English strings — which
 * is the opposite of the point. Widening to `string` keeps the *shape* enforced while
 * letting the words differ.
 */
export type Dictionary = typeof en

export default en
