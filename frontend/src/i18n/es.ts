import type { Dictionary } from './en'

/**
 * Castilian Spanish. Typed against the English dictionary, so a key added there and
 * forgotten here fails the build rather than the page.
 *
 * Register note: the storefront addresses the customer as "tú" throughout, not
 * "usted". The audience is people who play FUT, and "usted" would read as a bank.
 */
const es: Dictionary = {
  nav: {
    trading: 'Monedas',
    boosting: 'Boosting',
    coaching: 'Clases',
    rewards: 'Recompensas',
    track: 'Seguir pedido',
    faqs: 'Preguntas',
    tradersOnline: 'Traders conectados',
    myAccount: 'Mi cuenta',
    console: 'Consola',
    signIn: 'Iniciar sesión',
    signOut: 'Cerrar sesión',
    buyCoins: 'Comprar monedas',
    openMenu: 'Abrir menú',
    closeMenu: 'Cerrar menú',
    skipToContent: 'Ir al contenido',
    home: 'Inicio de Global FUT Services',
    language: 'Idioma',
    currency: 'Moneda',
  },

  footer: {
    tagline:
      'Servicio de trading y clases para EA FC. Trabajamos el mercado de traspasos en ' +
      'tu cuenta; las monedas se quedan contigo.',
    services: 'Servicios',
    menu: 'Menú',
    legal: 'Legal',
    follow: 'Síguenos',
    rewards: 'Recompensas',
    help: 'Centro de ayuda',
    support: 'Soporte',
    futClasses: 'Clases FUT',
    cards: 'Cartas',
    terms: 'Términos del servicio',
    privacy: 'Política de privacidad',
    aml: 'Prevención de blanqueo y KYC',
    about: 'Sobre nosotros',
    contact: 'Contacto',
    refund: 'Política de reembolso',
    cancellation: 'Política de cancelación',
    shipping: 'Política de envío',
    rights:
      'La moneda del juego, los objetos y las cartas de jugador son propiedad de ' +
      'Electronic Arts Inc. No estamos afiliados a EA.',
    legalLanguageNotice:
      'Nuestros Términos, la Política de privacidad y la política de prevención de ' +
      'blanqueo y KYC se publican en inglés, y la versión en inglés es la que rige.',
  },

  common: {
    loading: 'Cargando…',
    tryAgain: 'Reintentar',
    cancel: 'Cancelar',
    close: 'Cerrar',
    comingSoon: 'Próximamente',
  },

  loyalty: {
    otherCurrencyTitle: (loyalty) => `Los puntos se liquidan en ${loyalty}`,
    otherCurrencyBody: (shown, loyalty) =>
      `Los pedidos en ${shown} no acumulan puntos, y un saldo de puntos no puede ` +
      `usarse en ellos. Cambia la moneda a ${loyalty} para acumular y canjear.`,
  },

  proof: {
    eyebrow: 'En sus palabras',
    title: 'Lo que dicen los clientes',
    lead:
      'Opiniones reales de pedidos reales, sin editar. Filtra por el servicio que te '
      + 'interesa: las de coaching merecen una lectura antes de reservar.',
    all: 'Todo',
    trading: 'Monedas',
    boosting: 'Champs y Rivals',
    coaching: 'Clases',
    countOf: (shown, total) => `${shown} de ${total}`,
    range: (from: number, to: number, total: number): string => `${from}–${to} de ${total}`,
    prev: 'Anteriores',
    next: 'Siguientes',
    pageOf: (page: number, pages: number): string => `Página ${page} de ${pages}`,
    disclosure:
      'Cada opinión de esta página la escribió un cliente y se publica tal cual. Los '
      + 'resultados describen su propia experiencia: en coaching dependen del jugador, y '
      + 'nada de lo aquí escrito garantiza un rango ni un número de victorias.',
    translated: 'Traducido del inglés',
    originalLabel: 'Las palabras originales del cliente',
    showOriginal: 'Ver original',
    showTranslation: 'Ver traducción',
  },

  home: {
    seoTitle: (season) => `Servicio de trading seguro para EA ${season}`,
    seoDescription: (season) =>
      `Haz crecer tu club de EA ${season} sin el grindeo. Nuestros traders trabajan el ` +
      `mercado de traspasos en tu cuenta y las monedas se quedan contigo. Entrega en menos ` +
      `de una hora y 100% Safety Policy.`,

    promo: { label: 'Ofertas y novedades' },
    hero: {
      slideDiscount: 'Código de descuento GFS — 10% de descuento',
      slideBoosting: 'Boosting de Champs, Rivals y Objetivos',
      slideSocial: 'Sigue a Global FUT Services',
      liveNow: 'Traders conectados ahora',
      scroll: 'Desliza',
      ratesEyebrow: 'Precios en vivo',
      titleLead: 'El sitio más seguro, rápido y fiable para comprar',
      titleAccent: () => 'monedas FC, boosting & coaching',
      subLine: 'Una solución para cada problema de FC.',
      trustSpeed: 'La mayoría en menos de una hora',
      trustGuarantee: () => '100% Safety Policy',
      trustTax: 'Impuesto de EA ya incluido',
      body:
        'Nuestros traders trabajan el mercado de traspasos por ti: encuentran cartas ' +
        'infravaloradas, las revenden y dejan el beneficio en tu club. Pagas por el trabajo, ' +
        'no por monedas. La mayoría de pedidos llegan en menos de una hora.',
      startOrder: 'Hacer un pedido',
      reviewsLink: 'Lo que dijeron los clientes, en sus propias palabras',
      reviewsLinkNote: 'Pedidos reales · sin editar',
      seeBoosting: 'Boosting de Champs y Rivals',
      statDeliveryValue: '< 60 min',
      statDeliveryLabel: 'Entrega habitual',
      statShiftValue: '24 / 7',
      statShiftLabel: 'Traders de guardia',
      statGuaranteeValue: '100% Safety Policy',
      statGuaranteeLabel: 'Todos los pedidos cubiertos',
      cardTitle: 'Servicio de trading seguro',
      cardSubtitle: (season) => `Tarifas en vivo · ${season}`,
      popular: 'Popular',
      perMillion: '/ millón',
      ratesUpdating: 'Estamos actualizando las tarifas: vuelve en un momento.',
      orderFrom: (price) => `Desde ${price} / millón`,
      seePrices: 'Ver precios en vivo',
      taxIncluded:
        'Los precios incluyen la tasa del mercado de EA. No añadimos nada al final.',
    },

    trust: {
      delivery: 'La entrega más rápida',
      encrypted: 'Tus credenciales se cifran y se borran',
      payments: 'Tarjeta, UPI y transferencia',
      guarantee: 'Incluye la 100% Safety Policy',
      humans: 'Traders reales, no bots',
    },

    services: {
      eyebrow: (season) => `Lo que hacemos en ${season}`,
      title: 'Lleva tu club a lo más alto',
      lead:
        'Elige la que encaje. Todo tiene el precio por adelantado y todo está cubierto por ' +
        'la misma garantía.',
      tradingTag: 'Lo más pedido',
      tradingTitle: 'Servicio de trading seguro',
      tradingBody:
        'Trabajamos el mercado en tu cuenta y el beneficio se queda en tu club. Precio por ' +
        'millón y entrega en menos de una hora.',
      tradingCta: 'Hacer un pedido',
      boostTag: 'Listo para el finde',
      boostTitle: 'Champs y Rivals',
      boostBody:
        'Victorias conseguidas por jugadores que hacen esto cada semana. Elige tu objetivo ' +
        'y nos encargamos antes de que caigan las recompensas.',
      boostCta: 'Ver niveles',
      coachTag: 'Reservas abiertas',
      coachTitle: 'Clases FUT',
      coachBody:
        'Una hora, uno a uno, con un entrenador que juega al nivel que persigues. ' +
        'Una sesión suelta o un bloque de seis.',
      coachCta: 'Reservar sesión',
      pickTitle: '¿Qué estás buscando?',
      pickLead:
        'Tres cosas distintas, tres páginas distintas. Elige la que encaja con lo que '
        + 'quieres y te llevamos directo.',
    },

    why: {
      eyebrow: 'Por qué se quedan',
      title: 'Las seis cosas que ibas a preguntar',
      lead:
        'Respuestas directas, porque todo el que compra monedas tiene las mismas seis dudas ' +
        'y casi ninguna web responde a ninguna.',
      safeTitle: 'Seguro',
      safeBody: () =>
        'Cada pedido está cubierto por nuestra 100% Safety Policy tras la entrega. Si le '
        + 'pasa algo a tu cuenta en ese plazo, lo solucionamos.',
      simpleTitle: 'Sencillo',
      simpleBody:
        'Elige una cantidad, paga y dinos dónde enviarlo. La mayoría termina en dos minutos.',
      fastTitle: 'Rápido',
      fastBody:
        'Los pedidos de cualquier tamaño suelen llegar en menos de una hora. Nuestro plazo ' +
        'publicado es más largo a propósito: preferimos superarlo a discutirlo.',
      alwaysTitle: 'Siempre activo',
      alwaysBody:
        'Hay alguien de guardia a todas horas, incluidas las que importan para la liga del ' +
        'fin de semana.',
      privateTitle: 'Privado',
      privateBody:
        'Si un pedido necesita tus credenciales, se cifran antes de guardarse y se destruyen ' +
        'en cuanto termina el pedido. Te avisamos de cuándo cambiar la contraseña.',
      rewardTitle: 'Con recompensa',
      rewardBody: (points, spend, value) =>
        `Gana ${points} puntos por cada ${spend} que gastes y sube seis niveles que descuentan ` +
        `un porcentaje fijo de cada pedido. Cada punto vale ${value} al pagar.`,
      rewardBodyFallback: 'Gana puntos en cada pedido y gástalos en el siguiente.',
      rewardStatEarn: 'puntos ganados',
      rewardStatPer: (spend: string) => `por cada ${spend} gastados`,
      rewardStatTiers: 'niveles de fidelidad',
      rewardStatTiersNote: 'un descuento permanente en cada uno',
      rewardStatValue: 'por punto',
      rewardStatValueNote: 'canjeables al pagar',
      rewardCta: 'Cómo funcionan las recompensas',
    },

    how: {
      eyebrow: 'Cómo funciona',
      title: 'Estás pagando el trading, no las monedas',
      lead:
        'Importa, y no solo legalmente. Nunca te vendemos moneda del juego: esos activos son ' +
        'de EA. Lo que pagas es a gente que conoce el mercado trabajándolo por ti.',
      step1Title: 'Dinos la cantidad',
      step1Body:
        'Elige plataforma y cuántas monedas quieres mover. El precio que ves es el que pagas: ' +
        'la tasa del mercado de EA y la comisión ya están incluidas.',
      step2Title: 'Trabajamos el mercado',
      step2Body:
        'Nuestros traders encuentran cartas listadas por debajo de su valor, las compran y ' +
        'las revenden. Ese es el servicio que pagas.',
      step3Title: 'El beneficio se queda contigo',
      step3Body:
        'Las monedas acaban en tu club. Recibes un correo en cuanto está hecho, con la ' +
        '100% Safety Policy desde ese momento.',
    },

    rewards: {
      badge: 'Recompensas',
      title: 'Cada pedido paga parte del siguiente',
      body: (earn, value, cap) =>
        `Gana ${earn} que gastes. Cada punto vale ${value} y se descuenta automáticamente del ` +
        `siguiente pedido, hasta un ${cap} del total. Además te suben seis niveles, y el ` +
        `descuento de nivel no caduca.`,
      howItWorks: 'Cómo funcionan',
      createAccount: 'Crear una cuenta',
      pitchTitle: 'Cuanto más compras aquí, menos pagas',
      pitchOne: 'Puntos de descuento en cada pedido',
      pitchOneBody: (value, cap) =>
        `Cada punto vale ${value} al pagar, y hasta un ${cap} de un pedido puede pagarse ` +
        `con ellos. Sin códigos que recordar: el descuento ya está aplicado.`,
      pitchTwo: 'Un descuento de nivel que nunca caduca',
      pitchTwoBody: (top) =>
        `El gasto acumulado te sube por la escalera y nada te baja. Gastar tus puntos no ` +
        `te cuesta el nivel. El nivel más alto es ${top}.`,
      pitchThree: 'Gratis, y ganas desde el primer pedido',
      pitchThreeBody: (daily) =>
        `Solo hace falta una cuenta, y gana desde el primer pedido en lugar de tras un ` +
        `gasto mínimo. Entrar cada día suma ${daily} puntos.`,
      ladderTitle: 'Seis niveles',
      ladderOff: (pct) => `${pct} dto.`,
      guestNote: 'Los pedidos como invitado no acumulan ni guardan puntos.',
      exampleTitle: 'Ejemplo',
      youSpend: 'Gastas',
      youEarn: 'Ganas',
      worthAtCheckout: 'Valor al pagar',
      pointsLand:
        'Los puntos llegan cuando se cierra tu ventana de garantía, así un pedido reembolsado ' +
        'nunca te deja persiguiendo puntos que ya se retiraron.',
      pointsUnit: (points) => `${points} puntos`,
    },

    rail: {
      items: [
        'La entrega más rápida',
        'Traders de turno 24/7',
        'Tu acceso se cifra y luego se destruye',
        'UPI · Tarjetas · Banca en línea · Cripto',
        'Incluye la 100% Safety Policy',
        'Impuesto EA del 5 % cubierto',
      ],
    },

    proof: {
      eyebrow: 'Estándar de servicio',
      title: 'Las cifras que nos exigimos',
      lead:
        'No son cifras de marketing. Son los compromisos sobre los que está escrita la ' +
        'garantía, y por eso son conservadores.',
      deliveryValue: '10–60+',
      deliveryUnit: 'min',
      deliveryLabel: 'Entrega habitual',
      deliveryNote: 'El plazo publicado es más largo a propósito.',
      shiftValue: '24',
      shiftUnit: 'horas al día',
      shiftLabel: 'De turno, todos los días',
      shiftNote: 'Incluidas las horas de la weekend league.',
      guaranteeValue: '100%',
      guaranteeUnit: 'cubierto',
      guaranteeLabel: '100% Safety Policy',
      guaranteeNote: 'Reembolso completo o una cuenta de reemplazo — tú eliges.',
      tiersValue: '6',
      tiersUnit: 'niveles',
      tiersLabel: 'Niveles de fidelidad',
      tiersNote: 'El descuento de nivel nunca caduca.',
    },

    coach: {
      eyebrow: 'Uno a uno',
      title: 'Mejora en FC',
      body:
        'Una hora, en directo, con un entrenador que juega al nivel que persigues. Ve ' +
        'cómo juegas de verdad — no cómo crees que juegas — y corrige el hábito que más ' +
        'partidos te está costando.',
      point1: 'Tus propias repeticiones, pausadas y analizadas',
      point2: 'Tácticas hechas a medida de cómo defiendes',
      point3: 'Decisiones de plantilla que aguantan el meta',
      point4: 'Una sola cosa que entrenar antes de la próxima sesión',
      cta: 'Reservar una sesión',
      secondary: 'Cómo funciona',
      durationLabel: 'Duración',
      durationValue: (m) => (m % 60 === 0 ? `${m / 60} hora${m === 60 ? '' : 's'}` : `${m} min`),
      durationNote: 'Por sesión, en directo',
      formatLabel: 'Formato',
      formatValue: 'Uno a uno',
      formatNote: 'Nunca en grupo',
      validityLabel: 'Validez',
      validityValue: '1 mes',
      validityNote: 'Desde el día de la compra',
    },

    ask: {
      eyebrow: 'Antes de comprar',
      title: 'Pregunta lo que sea. Responde al instante.',
      body:
        'Precios, seguridad, tiempos de entrega, qué pasa con tu acceso — está todo en el chat ' +
        'de la esquina, respondido con la misma política con la que funciona el sitio. Si no ' +
        'puede ayudarte, te pasa con una persona.',
      cta: 'Abrir el chat',
    },

    closing: {
      eyebrow: 'Cuando quieras',
      title: 'Tu club está a un pedido de distancia',
      body:
        'Elige una cantidad, paga como prefieras y vuelve a jugar. Si algo se tuerce, estamos ' +
        'al otro lado del chat.',
      startOrder: 'Hacer un pedido',
      readFaqs: 'Leer las preguntas',
    },
  },

  comingSoon: {
    badge: 'Próximamente',
    body:
      'Lo estamos construyendo bien en lugar de publicar la mitad. Las monedas, el boosting ' +
      'y las clases ya están disponibles y tienen la misma garantía: empieza por ahí y te ' +
      'avisamos en cuanto esto se abra.',
    buyCoins: 'Comprar monedas',
    seeBoosting: 'Ver boosting',
    seoDescription: (service, season) => `${service} para EA ${season}: disponible muy pronto.`,
  },

  notFound: {
    title: 'No encontrado',
    heading: 'Esa página no existe',
    body: 'Si llegaste desde un correo de pedido, prueba a seguir el pedido en su lugar.',
    home: 'Inicio',
    track: 'Seguir un pedido',
  },

  boosting: {
    seoTitle: (season) => `Boosting de Champs y Rivals en ${season}`,
    seoDescription: (season) =>
      `Subidas de victorias en Champs y de división en Rivals para EA ${season}, con ` +
      `jugadores que lo hacen cada semana.`,
    eyebrow: (season) => `${season} · Boosting`,
    title: 'Deja el mando a alguien que juega a ese nivel',
    lead:
      'Victorias de Champs y subidas de Rivals, resueltas antes de que caigan las ' +
      'recompensas. La misma garantía, el mismo cifrado, la misma gente.',
    tabChamps: 'Victorias de Champs',
    tabRivals: 'Divisiones de Rivals',
    bestValue: 'Mejor valor',
    wins: 'victorias',
    successRateLabel: (pct) => `${pct} de acierto`,
    successRateNote: 'Medido sobre pedidos completados elegibles.',
    successHeadline: (range, pct) =>
    `${range} alcanzado en el ${pct} de los pedidos elegibles.`,
    choose: 'Elegir',
    tiersUpdating: 'Estamos actualizando los niveles de este servicio.',
    knowEyebrow: 'Lo que deberías saber',
    knowTitle: 'La versión honesta',
    signInTitle: 'Necesita tus credenciales',
    signInBody:
      'Alguien tiene que jugar los partidos, así que el boosting siempre es comfort trade. ' +
      'Tus datos se cifran antes de guardarse, solo los abre el jugador que lleva tu pedido ' +
      'y se destruyen al terminar.',
    timingTitle: 'El momento importa',
    timingBody:
      'Champs y Rivals funcionan con el reloj semanal de EA. Pide pronto dentro de la ' +
      'ventana y hay margen para trabajar; pide la última noche y puede que no lo haya.',
    discordTitle: 'Habla con nosotros en Discord',
    discordBody:
      'Si una serie no alcanza el rango que pediste, se te abona la diferencia de precio ' +
        'para usarla en otro pedido o solicitarla como reembolso. Las reclamaciones y dudas ' +
        'se gestionan en nuestro Discord oficial.',
    coveredTitle: 'Estás cubierto',
    coveredBody: (cash, credit) =>
      `Se aplica nuestra 100% Safety Policy. Si EA actúa contra la cuenta dentro de ` +
      `ese plazo, recibes el ${cash}% en efectivo o el ${credit}% en saldo: tú eliges.`,
  },


  track: {
    seoTitle: 'Seguir tu pedido',
    eyebrow: 'Estado del pedido',
    title: '¿Dónde está mi pedido?',
    lead:
      'Tu referencia y el correo que usaste. Los dos, porque una referencia por sí sola ' +
      'aparece en capturas y en chats de soporte.',
    reference: 'Referencia del pedido',
    email: 'Correo electrónico',
    find: 'Buscar mi pedido',
    emptyHint:
      'Tu referencia está en el correo de confirmación: tiene la forma GFS-26 seguido de ' +
      'ocho caracteres.',
    total: 'Total',
    placed: 'Realizado',
    deliveryMethod: 'Método de entrega',
    breakdown: 'Desglose',
    history: 'Historial',
    payTitle: 'Esperando el pago',
    payBody:
      'Este pedido aún no se ha pagado. Si cerraste la ventana de pago, empieza de nuevo ' +
      'desde la página del pedido y se reutilizará tu referencia.',
    // -- entrega en curso ---------------------------------------------------
    progressTitle: 'Monedas entregadas',
    progressOf: (done: string, total: string) => `${done} de ${total}`,
    action: {
      RESUBMIT_SIGN_IN: 'No se aceptaron tus credenciales de EA. Envíalas de nuevo abajo y continuamos.',
      NEW_BACKUP_CODES: 'Esos códigos de respaldo ya se usaron o son incorrectos. Genera otros en tu cuenta EA y envíalos abajo.',
      SIGN_OUT_CONSOLE: 'Cierra sesión de EA en la consola, la web app y la companion app. Lo reintentaremos solos.',
      CLEAR_UNASSIGNED_ITEMS: 'Tienes demasiados objetos sin asignar. Déjalos por debajo de 50 y lo reintentaremos.',
      FREE_TRANSFER_SLOTS: 'Tu lista de transferencias está llena. Libera al menos tres huecos en la lista y en los objetivos.',
      ADD_COINS: 'Tu club necesita más de 1.500 monedas antes de que podamos empezar.',
      SOLVE_CAPTCHA: 'EA te pide resolver un captcha. Entra una vez en la web app, complétalo y cierra sesión.',
      FIX_PERSONA: 'La persona de EA seleccionada no es la correcta. Cambia a la que tiene tu club.',
      ACCOUNT_UNUSABLE: 'Esta cuenta no se puede usar: no tiene acceso al mercado o no tiene club. Escríbenos y te devolvemos el dinero.',
      BANNED: 'EA ha restringido esta cuenta. Escríbenos: para esto está la garantía.',
      SUPPLIER_SIDE: 'Esto es cosa nuestra, no tuya. Estamos en ello y te avisamos aquí.',
    },
    credentialsTitle: 'Necesitamos tus credenciales para empezar',
    credentialsBody:
      'Inicia sesión en tu cuenta para enviarlas de forma segura. Se cifran antes de ' +
      'guardarse y se borran cuando termina el pedido.',
    // -- formulario de credenciales ----------------------------------------
    credFormTitle: 'Envía tus credenciales de EA',
    credFormLead:
      'Se cifran con una clave única para este pedido antes de guardarse, solo las abre el ' +
      'trader que lo gestiona, y se destruyen en cuanto el pedido se completa.',
    credFormRetention: (days: string) =>
      `Se eliminan automáticamente pasados ${days}, incluso si algo falla por nuestra parte.`,
    credEmail: 'Correo de la cuenta EA',
    credEmailHint: 'La dirección con la que inicias sesión en EA, no la de entrega.',
    credPassword: 'Contraseña de EA',
    credPasswordHint: 'Cámbiala en cuanto recibas el pedido — te lo recordaremos.',
    credShow: 'Mostrar contraseña',
    credHide: 'Ocultar contraseña',
    credBackupCodes: 'Códigos de respaldo',
    credBackupCodesFind: 'Cómo encontrar los códigos de respaldo',
    credBackupCodesHint: 'Uno por línea. Necesarios si tu cuenta pide un código al entrar.',
    credHandle: 'Gamertag o ID de PSN',
    credHandleHint: 'Opcional — ayuda al trader a confirmar que es la cuenta correcta.',
    credNote: '¿Algo más que debamos saber?',
    credAckSignedOut:
      'He cerrado sesión en consola, en la web app y en la companion app.',
    credAckMarket: 'Mi mercado de transferencias está desbloqueado.',
    credAckItems: 'Tengo menos de cinco objetos sin asignar.',
    credAckTerms: 'Entiendo que estos datos se guardan cifrados y se borran al completarse.',
    credSubmit: 'Enviar de forma segura',
    credSubmitting: 'Cifrando…',
    credDone: 'Ya tenemos tus credenciales',
    credDoneBody:
      'No tienes que hacer nada más. Están cifradas y se borran en cuanto el pedido se ' +
      'marca como completado.',
    credSignInFirst: 'Inicia sesión para enviarlas',
    credError: 'No se ha podido enviar. Revisa los campos e inténtalo de nuevo.',
    stuckTitle: 'Nos hemos atascado',
    stuckBody:
      'Normalmente significa que la cuenta estaba conectada, el mercado de traspasos ' +
      'bloqueado, o había demasiados objetos sin asignar. Escríbenos y lo resolvemos.',
    deliveredTitle: 'Entregado: dos cosas por hacer',
    deliveredBody: (until) =>
      `Cambia tu contraseña de EA y regenera tus códigos de respaldo. Todo lo que nos diste ` +
      `ya ha sido destruido. Tu garantía dura hasta el ${until}.`,
    reviewTitle: 'En revisión',
    reviewBody: 'Estamos revisando tu reclamación de garantía y te escribiremos.',
  },

  auth: {
      continueGoogle: 'Continuar con Google',
      continueDiscord: 'Continuar con Discord',
      orDivider: 'o',
      oauthNoEmail: 'Esa cuenta no compartió una dirección de correo, así que no pudimos iniciar sesión. Usa tu correo y contraseña.',
      oauthUnverified: 'Ya existe una cuenta con ese correo. Inicia sesión con tu contraseña una vez y luego podrás vincularlas.',
      oauthFailed: 'Ese inicio de sesión no se completó. Inténtalo de nuevo o usa tu correo y contraseña.',
    signInTitle: 'Iniciar sesión',
    registerTitle: 'Crear una cuenta',
    signInHeading: 'Bienvenido de nuevo',
    registerHeading: 'Crea tu cuenta',
    signInLead: 'Inicia sesión para ver tus pedidos y tus puntos.',
    registerLead: 'Gana puntos en cada pedido y ten tu historial en un solo sitio.',
    email: 'Correo electrónico',
    password: 'Contraseña',
    passwordHint:
      'Al menos 12 caracteres. Una frase corta funciona bien y se recuerda mejor que una ' +
      'palabra retorcida.',
    displayName: 'Nombre visible',
    acceptPrefix: 'Acepto los',
    acceptTerms: 'términos del servicio',
    acceptAnd: 'y la',
    acceptPrivacy: 'política de privacidad',
    createAccount: 'Crear cuenta',
    signInButton: 'Iniciar sesión',
    haveAccount: '¿Ya tienes cuenta?',
    newHere: '¿Primera vez?',
    signInLink: 'Iniciar sesión',
    createLink: 'Crea una',
    guestNote:
      'No necesitas cuenta para pedir: el pago como invitado funciona igual. La cuenta es ' +
      'la forma de acumular puntos.',
    genericError: 'Algo ha ido mal. Inténtalo de nuevo.',
  },

  support: {
    seoTitle: 'Soporte',
    seoDescription: 'Ponte en contacto sobre un pedido.',
    eyebrow: 'Soporte',
    title: 'Cuéntanos qué ocurre',
    lead:
      'Hay alguien de guardia a todas horas. Incluye la referencia de tu pedido si la ' +
      'tienes y lo localizamos al instante.',
    yourEmail: 'Tu correo',
    orderRef: 'Referencia del pedido',
    orderRefHint: 'Opcional, pero acelera las cosas.',
    subject: 'Asunto',
    message: '¿Qué está pasando?',
    noPassword: 'No he incluido mi contraseña ni mis códigos de respaldo en este mensaje.',
    noPasswordNote:
      'Nunca te los pediremos aquí. Si un pedido necesita tus credenciales, se recogen en el ' +
      'formulario cifrado del propio pedido.',
    send: 'Enviar mensaje',
    sendFailed: 'No se ha podido enviar.',
    fasterTitle: 'Respuestas más rápidas',
    faster1: '¿El pedido no avanza? Comprueba primero que has cerrado sesión en todas partes.',
    faster2: '¿Importe incorrecto? Envíanos la referencia y una captura.',
    faster3: '¿Baneo o retirada de monedas? Incluye la fecha en que ocurrió.',
    neverTitle: 'Lo que nunca haremos',
    neverBody:
      'Pedirte la contraseña por chat o correo, pedirte que pagues fuera de la web, o ' +
      'escribirte primero por un «problema» con tu pedido. Si alguien lo hace, no somos nosotros.',
  },

  account: {
    seoTitle: 'Mi cuenta',
    greeting: (name) => `Hola, ${name}`,
    fallbackTitle: 'Tu cuenta',
    yourOrders: 'Tus pedidos',
    noOrders: 'Aún no hay pedidos',
    noOrdersBody: 'Cuando hagas uno aparecerá aquí, con todo su historial.',
    statement: 'Extracto de puntos',
    rewardPoints: 'Puntos de recompensa',
    worth: 'valen',
    atCheckout: 'al pagar',
    earnedTotal: (total, cap) =>
      `Has ganado ${total} en total. Los puntos pueden cubrir hasta el ${cap}% de un pedido ` +
      `y llegan cuando se cierra cada ventana de garantía.`,
    coaching: 'Clases',
    sessionsLeftOne: 'sesión por reservar',
    sessionsLeftMany: 'sesiones por reservar',
    useThemBy: (date) => `Úsalas antes del ${date}.`,
    bookSession: 'Reservar sesión',
    manageSessions: 'Gestionar sesiones',
    quickActions: 'Acciones rápidas',
    startOrder: 'Hacer un pedido',
    needChange: '¿Necesitas cambiar algo de un pedido en curso?',
    contactSupport: 'Contacta con soporte',
    withReference: 'con la referencia.',
  },

  order: {
    seoTitle: (season) => `Comprar monedas de ${season}`,
    seoDescription:
      'Elige plataforma y cantidad. Precios en vivo, sin cargos ocultos al pagar.',
    pricesUnavailable: 'Los precios no están disponibles',
    title: 'Configura tu pedido',
    lead: 'Todo tiene el precio por adelantado. No añadimos nada en el último paso.',
    stepPackage: (_n, service) => service,
    stepPlatform: () => 'Plataforma',
    stepAmount: () => 'Cantidad',
    stepDiscounts: () => 'Descuentos',
    perMillion: '/ millón',
    taxIncludedShort: 'Incluido',
    taxIncludedInline: 'Impuesto EA del 5% incluido',
    taxIncludedTitle: 'El 5% de EA corre de nuestra cuenta',
    taxIncludedBody:
      'EA se lleva el 5% de cada traspaso. La mayoría te lo suma en el checkout. ' +
      'Nosotros no: el precio por millón que ves es el que pagas.',
    amountAria: 'Cantidad de monedas en millones',
    savingsTitle: 'Descuentos y recompensas',
    savingsEmpty: 'Aún sin descuento — un cupón o puntos aparecerían aquí.',
    youSave: 'Ahorras',
    amountManualLabel: 'O escribe una cantidad exacta',
    amountManualHint: (stepK, minK, maxK) =>
      `En miles, de ${stepK}K en ${stepK}K. Entre ${minK} y ${maxK}.`,
    amountManualUnit: 'K',
    amountSnapped: (shown) => `Ajustado al paso más cercano — ${shown}.`,
    requirementsTitle: 'Requisitos para pedir',
    requirementsLead:
      'Cuatro cosas deben cumplirse antes de poder mover monedas. Conviene comprobarlas ' +
      'ahora: cada una es un pedido que si no se queda parado después de pagar.',
    reqCompanion: 'Acceso a la Web o Companion App',
    reqCompanionNote: 'Así se hace el intercambio. Sin acceso, no hay traspaso.',
    reqMarket: 'Mercado de traspasos desbloqueado',
    reqMarketNote: 'Una cuenta nueva o reciente suele seguir bloqueada.',
    reqMinCoins: 'Al menos 5.000 monedas en el club',
    reqMinCoinsNote: 'Para poner una carta en venta hace falta saldo inicial.',
    reqUnassigned: 'Menos de 5 objetos sin asignar',
    reqUnassignedNote: 'Una pila llena bloquea los traspasos por completo.',
    deliveryFixed: 'GFS Trading Method 3.0 (Latest)',
    deliveryFixedHint:
      'Nuestro método actual, usado en todos los pedidos de monedas. No hay nada que elegir.',

    /*
     * Aqui «B» seria un error de mil veces: en espanol un billon es 10^12, asi que
     * «5,5 B» prometeria cinco billones y medio de monedas. Se expresa en millones,
     * que es la unidad que el resto de la pagina ya usa.
     */
    volumeLabel: 'Monedas transferidas',
    volumeValue: '5.500 M+',
    volumeNote: (season: string) => `En pedidos de clientes en ${season}.`,
    couponLabel: 'Código de descuento',
    couponHint: '¿Tienes un código? Introdúcelo aquí.',
    couponApplied: (code) => `${code} aplicado.`,
    pointsLabel: 'Puntos de recompensa',
    pointsHintUsable: (balance, usable) =>
      `Tienes ${balance}. Puedes usar hasta ${usable} aquí.`,
    pointsHintPlain: (balance) => `Tienes ${balance} puntos.`,
    createAccount: 'Crea una cuenta',
    createAccountRest: 'para ganar puntos con este pedido y gastarlos en el siguiente.',

    summaryTitle: 'Tu pedido',
    total: 'Total',
    earnsPoints: (points) =>
      `Ganas ${points} puntos cuando se cierre tu ventana de garantía.`,
    continue: 'Continuar',
    priceNote:
      'Los precios se mantienen unos minutos y se vuelven a comprobar al pagar, así que lo ' +
      'que ves aquí es lo que se cobra.',
    refreshingPrice: 'Actualizando el precio…',
    priceHeld: (time) => `Precio reservado ${time}`,

    whereTitle: '¿Dónde lo enviamos?',
    email: 'Correo electrónico',
    emailHint: 'Aquí llegan tu recibo y el aviso de entrega.',
    phone: 'Teléfono',
    phoneHint: 'Opcional, solo por si nos atascamos.',
    eaName: 'Nombre de cuenta de EA',
    eaNameHint: 'Para que el trader te encuentre.',
    eaNamePlaceholder: 'Gamertag o ID de EA',
    deliveryMethod: 'Método de entrega',
    deliveryAuctionHint:
      'Tú listas una carta y nosotros la compramos en el mercado. Te pedimos tus '
      + 'credenciales después del pago; se cifran y se destruyen al terminar el pedido.',
    deliveryComfortHint:
      'Iniciamos sesión y comerciamos directamente. Más rápido para cantidades grandes; te ' +
      'pediremos tus credenciales después del pago.',
    deliveryAuction: 'Mercado de traspasos: tú listas, nosotros compramos',
    deliveryComfort: 'Comfort trade: entramos por ti',
    coachingNextTitle: 'Qué pasa ahora',
    coachingNextBody:
      'Tus sesiones aparecen en tu cuenta en cuanto se confirma el pago, y las reservas ' +
      'desde la página de clases cuando quieras. Para las clases nunca necesitamos tus ' +
      'credenciales de EA: juegas en tu propia cuenta mientras tu entrenador observa.',
    signInTitle: 'Sobre tus credenciales',
    signInBody:
      'Te las pediremos después del pago, nunca antes. Se cifran en cuanto nos llegan, solo ' +
      'las abre el trader que lleva tu pedido y se destruyen al terminar. Te recordaremos ' +
      'que cambies la contraseña después.',
    noteLabel: '¿Algo que debamos saber?',
    notePlaceholder: 'Opcional',
    beforeYouPay: 'Antes de pagar',
    readyCheck:
      'Mi mercado de traspasos está desbloqueado, tengo al menos 5.000 monedas y menos de ' +
      'cinco objetos sin asignar, y he cerrado sesión en consola, web app y companion app.',
    termsPrefix: 'He leído los',
    termsLink: 'términos del servicio',
    termsCoaching: 'y entiendo que estoy comprando un servicio de clases.',
    termsTrading: 'y entiendo que compro un servicio de trading, no moneda del juego.',
    back: 'Atrás',
    pay: (amount) => `Pagar ${amount}`,
    acceptTermsError: 'Acepta los términos para realizar el pedido.',
    readyChecksError: 'Confirma que tu cuenta está lista: nos ahorra un retraso a los dos.',

    orderCreated: 'Pedido creado',
    keepReference:
      'Guarda esa referencia: es como sigues el pedido y como soporte lo encuentra.',
    stubTitle: 'La pasarela de pago no está configurada',
    stubBody:
      'Este entorno funciona sin credenciales de pago reales, así que no se moverá dinero. ' +
      'El pedido existe y es visible en la consola de operaciones. Configura las claves de ' +
      'Razorpay para habilitar pagos reales.',
    payWindowFailed: 'La ventana de pago no se ha abierto. Revisa tu conexión e inténtalo de nuevo.',
    trackOrder: 'Seguir este pedido',
    coachingNextStub:
      'Las sesiones se añaden cuando se confirma un pago. En este entorno ningún pago '
      + 'puede confirmarse, así que no se ha añadido ninguna y el calendario seguirá '
      + 'oculto. En el sitio real aquí es donde irías a reservar.',
    coachingNextCta: 'Ir a reservar',
    coachingNextCtaNow: 'Reserva tu primera sesión',
  },

  help: {
    seoTitle: 'Centro de ayuda',
    seoDescription: 'Respuestas a las preguntas que la gente hace de verdad antes de pedir.',
    eyebrow: 'Centro de ayuda',
    title: 'Las preguntas que la gente hace de verdad',
    lead:
      'Si la respuesta no está aquí, soporte está a un mensaje y siempre hay alguien de turno.',

    groupBefore: 'Antes de pedir',
    groupOrdering: 'Pedido y entrega',
    groupMoney: 'Dinero y soporte',

    qServices: '¿Qué servicios ofrece Global FUT Services?',
    aServices:
      'Ofrecemos tres servicios principales para mejorar tu experiencia en FUT: ' +
      'transferencias de monedas FUT seguras y rápidas, boosting de FUT Champs para ' +
      'alcanzar el rango que quieres, y clases personalizadas de FUT diseñadas para ' +
      'mejorar tu juego.',

    qSafety: '¿Cómo puedo estar seguro de que mi cuenta está a salvo?',
    aSafety:
      'La seguridad de tu cuenta es nuestra máxima prioridad. Con más de cuatro años en ' +
      'el sector y un historial de dos mil millones de monedas transferidas y 1.400 boosts ' +
      'completados, usamos métodos seguros y probados que protegen tu cuenta en cada paso.',

    qPartners: '¿Tenéis acuerdos con fuentes de confianza?',
    aPartners:
      'Sí. Colaboramos con los canales de YouTube Vibhor Sharma y FC Breakdown desde hace ' +
      'casi tres años. Esas colaboraciones reflejan nuestro compromiso con la integridad y ' +
      'con un servicio en el que puedes confiar.',

    qSpeed: '¿En cuánto tiempo veré resultados?',
    aSpeed:
      'Las transferencias de monedas se entregan en 10 a 30 minutos. El boosting de Champs ' +
      'se hace con esperas mínimas. Las clases se programan a la hora que mejor te venga.',

    qOrdering: '¿Cómo hago un pedido?',
    aOrdering:
      'Configura tu pedido en la web, elige plataforma y cantidad, y paga. Recibirás una ' +
      'referencia de pedido al momento y nuestro equipo se encarga a partir de ahí. Si ' +
      'prefieres hablarlo antes, escribe a soporte y alguien te ayudará.',

    qCredentials: '¿Qué necesitáis de mí para comprar o vender monedas?',
    aCredentials:
      'Para cualquier pedido de monedas — con ambos métodos de entrega — necesitamos tu ' +
      'correo y contraseña de EA y tres códigos de ' +
      'respaldo de EA, y te los pedimos solo después de que hayas pagado. Asegúrate de que ' +
      'tu cuenta tenga acceso al mercado de fichajes en la EA Web App para que todo vaya ' +
      'bien. Cuando el pedido esté en marcha recibirás una confirmación, y lo mejor es no ' +
      'iniciar sesión hasta que terminemos para que nada se interrumpa.',

    qTax: '¿Y el 5% de impuesto de EA en mi compra de monedas?',
    aTax:
      'EA se queda con un 5% de cada traspaso en el mercado — es un cargo de EA, no ' +
      'nuestro. Lo mostramos como una línea propia en tu presupuesto antes de pagar, en ' +
      'lugar de esconderlo en el precio, para que la cifra que ves sea la que se te cobra. ' +
      'No se añade nada después.',

    qPayment: '¿Qué métodos de pago aceptÃ¡is?',
    aPayment:
      'UPI — incluidos GPay, PhonePe, Paytm y CRED — además de tarjetas de débito y ' +
      'crédito y banca online, todo gestionado por Razorpay. PayPal está disponible bajo ' +
      'petición y tiene una comisión adicional. Skrill y Bitcoin aún no están activos.',

    qBackupCodes: '¿Cómo encuentro mis códigos de respaldo?',
    aBackupCodes:
      'Los códigos de respaldo vienen de tu cuenta de EA, no de nosotros. Inicia sesión '
      + 'en los ajustes de tu cuenta de EA, abre la sección de Seguridad y busca la '
      + 'verificación de inicio de sesión: ahí están los códigos de respaldo y puedes '
      + 'generar un juego nuevo cuando quieras. Copia la lista entera y pégala; usamos '
      + 'uno y el resto siguen siendo tuyos. Genera unos nuevos al terminar tu pedido y '
      + 'los antiguos dejarán de funcionar. Si la sección no está donde se describe, EA '
      + 'la ha movido: escríbenos por Discord y te guiamos por las pantallas actuales.',
    qRefund: '¿Cuál es vuestra política de reembolsos?',
    aRefund:
      'Una vez que hemos empezado, un pedido no se puede cancelar sin más, porque las ' +
      'monedas ya se están moviendo. Aun así no te quedas sin cobertura: cada pedido ' +
      'lleva una garantía de 7 días desde la entrega, y si la reclamación prospera eliges ' +
      'el 100% en saldo de tienda o el 50% en efectivo. Si no podemos completar un pedido ' +
      '— por ejemplo, una cuenta sin acceso al mercado — se te reembolsa.',

    qSupport: '¿Y si tengo dudas durante el proceso?',
    aSupport:
      'Estamos disponibles a todas horas. Nuestro equipo de soporte 24/7 siempre está ' +
      'listo para responder una duda o ayudarte con un problema: escríbenos cuando quieras.',

    stillStuck: '¿Sigues atascado?',
    stillStuckBody: 'Cuéntanos qué pasa y lo cogemos nosotros.',
    contactSupport: 'Contactar con soporte',
  },

  rewards: {
    seoTitle: 'Recompensas',
    seoDescription:
      'Gana puntos en cada pedido, sube seis niveles y consigue un descuento automático al ' +
      'pagar que nunca caduca.',
    loading: 'Cargando el programa de recompensas…',
    eyebrow: 'Recompensas',
    title: 'Dos formas de recuperar dinero',
    lead:
      'Cada pedido gana puntos que puedes gastar en el siguiente. Esos mismos puntos te suben ' +
      'seis niveles, y cada nivel descuenta un porcentaje fijo de todo lo que pidas después.',
    earningLabel: 'Ganas',
    earningUnit: (points) => `${points} pts`,
    earningPer: (spend) => `por cada ${spend} que gastes`,
    spendingLabel: 'Gastas',
    spendingPer: 'por punto al pagar',
    topLabel: 'En lo más alto',
    effectiveLabel: 'Efectivo',
    topBody: (tier) => `de vuelta en puntos, más un descuento ${tier} en cada pedido`,
    effectiveBody: 'de vuelta en todo lo que pidas',
    tiersTitle: 'Los seis niveles',
    tiersLead:
      'Los niveles se ganan con los puntos acumulados de por vida: el total de todo lo que has ' +
      'ganado. Gastar tu saldo nunca te baja de nivel, y tu descuento se aplica ' +
      'automáticamente al pagar sin tener que introducir nada.',
    colTier: 'Nivel',
    colPoints: 'Puntos acumulados',
    colDiscount: 'Descuento',
    fromFirstOrder: 'Desde tu primer pedido',
    rulesTitle: 'Las reglas, completas',
    rulePointsLand: 'Los puntos llegan al cerrarse tu garantía.',
    rulePointsLandBody: (days) =>
      `${days} días después de la entrega, no al pagar. Así un pedido reembolsado nunca te ` +
      `deja persiguiendo puntos que ya se retiraron.`,
    ruleCap: (cap) => `Hasta el ${cap} de un pedido puede pagarse con puntos.`,
    ruleCapBody:
      'Un tope simplifica los reembolsos y evita que el sistema se convierta en algo que farmear.',
    ruleNoDemote: 'Gastar puntos nunca te baja de nivel.',
    ruleNoDemoteBody:
      'Tu nivel depende de lo acumulado de por vida, así que usar la recompensa que te dimos ' +
      'no te cuesta el estatus que ganaste consiguiéndola.',
    ruleDaily: (points) => `${points} puntos al día, solo por pasarte.`,
    ruleDailyBody:
      'Una vez al día, desde tu cuenta. Se gastan como cualquier otro punto y cuentan para tu nivel.',
    ruleNoCash: 'Los puntos no tienen valor en efectivo.',
    ruleNoCashBody:
        'Son un descuento en pedidos futuros, no un saldo retirable. Los pedidos como invitado '
        + 'no pueden ganarlos ni almacenarlos.',
    startedTitle: 'Empezar',
    startedBody:
      'Los puntos necesitan una cuenta: un pedido como invitado no tiene dónde guardarlos. ' +
      'Crear una lleva un momento y tu saldo se ve desde el primer pedido, con un extracto ' +
      'que muestra de dónde salió cada punto.',
    createAccount: 'Crear una cuenta',
    startOrder: 'Hacer un pedido',
    standingEyebrow: 'Dónde estás',
    discountOffEvery: (pct) => `${pct} de descuento en cada pedido`,
    lifetimeLine: (lifetime, balance) =>
      `${lifetime} puntos acumulados · ${balance} disponibles para gastar`,
    toNextTier: (points, tier) => `${points} puntos más para ${tier}.`,
    claim: (points) => `Reclamar +${points}`,
    claimedToday: (points) => `Ya has reclamado tus ${points} puntos de hoy.`,
    comeBack: 'Vuelve mañana.',
    claimFailed: 'No se ha podido reclamar el bono de hoy.',
  },

  coaching: {
    seoTitle: (season) => `Clases de FUT ${season}`,
    seoDescription: (season) =>
      `Clases individuales de EA ${season}: construcción de plantilla, trading y los hábitos ` +
      `que de verdad suben tu rango. Reserva una sesión suelta o un bloque de seis.`,
    eyebrow: (season) => `${season} · Clases`,
    title: 'Mejora a propósito, no por casualidad',
    lead:
      'Una hora, uno a uno, sobre tu plantilla y tus hábitos. Te vemos jugar, paramos ' +
      'donde importa y te dejamos algo que puedas repetir.',
    creditsLeft: (n) => (n === 1 ? 'sesión' : 'sesiones'),
    creditsAlertPrefix: 'Te quedan',
    creditsAlertSuffix: '. Elige entrenador y hora abajo.',
    loadCoachesFailed: 'No se han podido cargar los entrenadores.',
    needMore: '¿Necesitas más sesiones?',
    pricingHeading: 'Precios del coaching',
    packBody:
      'Seis sesiones para usar en un mes. Resérvalas sobre la marcha, una a una, al ritmo que ' +
      'te venga bien.',
    singleBody:
      'Una sesión. Una buena forma de ver si esto es para ti antes de comprometerte con un bloque.',
    buyBlock: 'Comprar el paquete',
    buySession: 'Comprar una sesión',
    saveBadge: (pct) => `Ahorra ${pct}%`,
    whoTitle: 'Conoce a tu entrenador',
    peak: 'Máximo:',
    speaks: 'Habla:',
    bookTitle: 'Reservar una sesión',
    noCoachesTitle: 'Ahora mismo no hay entrenadores aceptando reservas',
    noCoachesBody:
      'Tus sesiones aún no caducan: te escribiremos en cuanto alguien abra hueco.',
    previousMonth: 'Mes anterior',
    nextMonth: 'Mes siguiente',
    dayAvailable: 'horas disponibles',
    dayUnavailable: 'sin horas',
    noSlotsInMonth: (month) => `No hay huecos en ${month}`,
    timesShownIn: 'Horas mostradas en',
    coachesFrom: (name, zone) => `${name} entrena desde ${zone}`,
    coachDiscord:
      'Tras el pedido, contacta con tu entrenador o con Soporte GFS en nuestro Discord ' +
      'oficial para la programación y la comunicación de la sesión:',
    policyLine: (minutes, hours) =>
      `${minutes} minutos · puedes cambiarla gratis hasta ${hours} h antes`,
    bookedFor: 'Reservada para el',
    emailedDetails: '. Te hemos enviado los detalles por correo.',
    loadSlotsFailed: 'No se ha podido cargar la disponibilidad.',
    bookingFailed: 'La reserva no se ha completado.',
    noSlotsTitle: 'No hay horas libres en las próximas dos semanas',
    noSlotsBody: (name) =>
      `${name} está completo. Prueba con otro entrenador o vuelve más tarde: las ` +
      `cancelaciones liberan huecos con frecuencia.`,
    upcomingTitle: 'Tus próximas sesiones',
    alsoAppears: 'Cada sesión aparece también en',
    yourAccount: 'tu cuenta',
    withCoach: 'con',
    join: 'Entrar',
    cancel: 'Cancelar',
    cancelRefunds: '¿Cancelar esta sesión? El crédito vuelve a tu saldo.',
    cancelForfeits:
      '¿Cancelar esta sesión? Estás dentro del plazo de aviso, así que la sesión se pierde.',
    cancelFailed: 'No se ha podido cancelar esa sesión.',
  },

  chat: {
    launcher: 'Haz una pregunta',
    close: 'Cerrar',
    assistant: 'Asistente FUT',
    status: 'Responde al instante',
    greeting:
      'Hola. Puedo responder al momento las preguntas que más nos hacen. Elige una abajo, o ' +
      'habla con una persona si lo prefieres.',
    suggestions: 'Preguntas frecuentes',
    more: 'Más preguntas',
    typing: 'Escribiendo',
    anythingElse: '¿Algo más?',
    talkToHuman: 'Hablar con una persona',
    humanReply:
      'Esa la responde mejor una persona: hay alguien de guardia y lo cogerá enseguida.',
    openSupport: 'Escribir a soporte',
    readFull: 'Ver el centro de ayuda completo',
    automated:
      'Respuestas automáticas de nuestro centro de ayuda. Una persona está siempre a un clic.',
    transcriptTitle: 'Las preguntas que la gente hace de verdad',
  },

  search: {
    open: 'Buscar',
    label: 'Buscar en el sitio',
    placeholder: 'Busca p\u00e1ginas, respuestas y servicios\u2026',
    close: 'Cerrar la b\u00fasqueda',
    clear: 'Borrar la b\u00fasqueda',
    empty: 'Escribe para buscar p\u00e1ginas, respuestas y servicios.',
    noResults: (query: string) => `No hay nada que coincida con \u00ab${query}\u00bb.`,
    noResultsHint: 'Prueba con el nombre de un servicio, una pregunta o una referencia de pedido.',
    results: (count: number) => (count === 1 ? '1 resultado' : `${count} resultados`),
    groupActions: 'Ir a',
    groupPages: 'P\u00e1ginas',
    groupServices: 'Servicios',
    groupFaq: 'Respuestas',
    trackOrder: (ref: string) => `Seguir el pedido ${ref}`,
    trackOrderHint: 'Abre el seguimiento con esta referencia ya escrita',
    hintMove: 'para moverte',
    hintOpen: 'para abrir',
    hintClose: 'para cerrar',
    pages: {
      home: { label: 'Inicio', keywords: 'inicio portada principal home global fut services' },
      order: { label: 'Comprar monedas', keywords: 'comprar monedas coins pedido precio coste cuanto cuesta pagar plataforma pc ps5 playstation xbox millon millones trading' },
      boosting: { label: 'Boosting', keywords: 'boosting boost champs rivals liga fin de semana victorias division rango precio coste cuanto cuesta' },
      coaching: { label: 'Clases', keywords: 'clases coaching entrenador entrenamiento sesion reservar mejorar profesor precio coste cuanto cuesta tarifa' },
      rewards: { label: 'Recompensas', keywords: 'recompensas puntos fidelidad niveles descuento ganar gastar monedero cashback' },
      track: { label: 'Seguir pedido', keywords: 'seguir seguimiento estado pedido referencia entrega donde esta mi pedido' },
      help: { label: 'Centro de ayuda', keywords: 'ayuda faq faqs preguntas respuestas garantia baneo reembolso seguro' },
      support: { label: 'Soporte', keywords: 'soporte contacto mensaje correo problema queja incidencia' },
      account: { label: 'Mi cuenta', blurb: 'Tus pedidos, tu saldo de puntos y tus datos.', keywords: 'cuenta perfil mis pedidos historial puntos saldo ajustes' },
      login: { label: 'Iniciar sesi\u00f3n', blurb: 'Entra, o crea una cuenta para empezar a ganar puntos.', keywords: 'iniciar sesion entrar login registrarse crear cuenta contrasena' },
      cards: { label: 'Cartas de jugador', blurb: 'Las cartas de jugador aún no están abiertas. Esto es lo que viene.', keywords: 'cartas jugadores iconos proximamente' },
      terms: { label: 'T\u00e9rminos del servicio', blurb: 'El contrato: entrega, garantías, reembolsos y qué es definitivo.', keywords: 'terminos legal contrato condiciones reembolso politica' },
      privacy: { label: 'Pol\u00edtica de privacidad', blurb: 'Qué recogemos, cuánto lo guardamos y cómo pedir que se borre.', keywords: 'privacidad legal datos informacion personal cookies rgpd gdpr' },
      aml: { label: 'AML y KYC', blurb: 'Cómo verificamos la identidad y por qué a veces tenemos que preguntar.', keywords: 'aml kyc legal blanqueo de capitales identidad verificacion' },
    },
  },
  catalog: {
    services: {
      TRADING_SERVICE: 'Servicio de trading seguro',
      BOOST_CHAMPS: 'Boosting de Champs',
      BOOST_RIVALS: 'Boosting de Rivals',
      COACHING: 'Clases FUT',
      CARDS: 'Cartas de jugador',
    },
    variants: {
      WINS_9: '9 victorias · Champion II',
      WINS_10: '10 victorias · Champion I',
      WINS_11: '11 victorias · Elite V · Rank 5',
      WINS_12: '12 victorias · Elite IV · Rank 4',
      WINS_13: '13 victorias · Elite III · Rank 3',
      WINS_14: '14 victorias · Elite II · Rank 2',
      WINS_15: '15 victorias · Elite I · Rank 1',
      WINS_EXTRA_8: '+8 victorias extra',
      DIV_5_TO_4: 'De División 5 a 4',
      DIV_4_TO_3: 'De División 4 a 3',
      DIV_3_TO_2: 'De División 3 a 2',
      DIV_2_TO_1: 'De División 2 a 1',
      DIV_1_TO_ELITE: 'De División 1 a Elite',
      SINGLE_SESSION: 'Sesión única · 1 hora',
      MONTHLY_6_SESSIONS: '6 sesiones × 40 minutos',
    },
    millions: (qty: string): string => `${qty} M`,
    lines: {
      base: (service: string, detail: string): string => `${service} — ${detail}`,
      marketTax: (pct: string): string => `Impuesto del mercado de fichajes de EA (${pct})`,
      gatewayFee: (pct: string): string => `Gastos de procesamiento del pago (${pct})`,
      walletRedemption: (points: number): string => `${points} puntos canjeados`,
      coupon: (code: string): string => `Cupón ${code}`,
      referral: (code: string): string => `Código de creador ${code}`,
      tierDiscount: 'Descuento de miembro',
    },
  },
}

export default es
