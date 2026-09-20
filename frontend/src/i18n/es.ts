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
    seoTitle: (season) => `Comprar monedas para EA ${season}`,
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
      cardTitle: 'Comprar monedas',
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
      tradingTitle: 'Comprar monedas',
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
    successRateNote:
      'Cifras publicadas por GFS, sobre pedidos en los que la cuenta cumplía los requisitos.',
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


  cookies: {
    title: 'Cookies en este sitio',
    body:
      'Usamos una cookie para mantener tu sesión iniciada. Si nos lo permites, también '
      + 'recordamos tu idioma y tu moneda en este navegador. Sin rastreo, sin publicidad y '
      + 'sin compartir nada con nadie.',
    policyLink: 'Leer la política de privacidad',
    accept: 'Aceptar',
    decline: 'Rechazar',
  },
  notifications: {
    bell: 'Notificaciones',
    bellWithCount: (n) => `Notificaciones, ${n} sin leer`,
    title: 'Notificaciones',
    empty: 'Nada todavía. Aquí verás las novedades de tus pedidos.',
    dismiss: 'Cerrar',
  },
  track: {
    stagePaymentVerified: 'Pago verificado',
    stageQueued: 'En cola para entrega',
    stageInProgress: 'En curso',
    stageCompleted: 'Completado',
    myOrdersTitle: 'Mis pedidos',
    myOrdersLead: 'Consulta y sigue todos tus pedidos en un solo sitio.',
    tabAll: 'Todos',
    tabBoosting: 'Boosting',
    tabCoaching: 'Coaching',
    tabTrading: 'Monedas',
    tabCompleted: 'Completados',
    viewDetails: 'Ver detalles',
    backToOrders: 'Volver a mis pedidos',
    noOrdersYet: 'Aún no hay pedidos. El primero aparecerá aquí.',
    noOrdersInTab: 'Aquí no hay nada todavía.',
    guestLookupOpen: '¿Buscas un pedido hecho sin cuenta?',
    guestLookupClose: 'Ocultar la búsqueda por referencia',
    orderList: 'Lista de pedidos',
    refreshedLive: 'Esta página se actualiza sola.',
    discordTicketTitle: 'Entra en tu ticket de Discord',
    discordTicketBody: 'Continúa tu pedido en Discord. Nuestro equipo está listo para ayudarte.',
    discordTicketCta: 'Abrir Discord',
    discordTicketCreated: '¡Tu ticket está creado!',
    discordTicketQuote: (ref) => `Indica tu número de pedido, ${ref}, y el equipo lo atenderá.`,
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
    deliveryTrading: 'GFS Trading Method 3.0 (Latest)',
    deliveryBoosting: 'Jugado en tu cuenta',
    deliveryCoaching: 'Sesión programada',
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
      ADD_COINS: 'Tu club necesita al menos 5.000 monedas antes de que podamos empezar.',
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
    howToFind: '¿Cómo encontrarlo?',
    credEmail: 'Correo de la cuenta EA',
    credEmailHint: 'La dirección con la que inicias sesión en EA, no la de entrega.',
    credPassword: 'Contraseña de EA',
    credPasswordHint: 'Cámbiala en cuanto recibas el pedido — te lo recordaremos.',
    credShow: 'Mostrar contraseña',
    credHide: 'Ocultar contraseña',
    credBackupCodeN: (n) => `Código de respaldo ${n}`,
    credReassureLead: 'Tus datos de cuenta se cifran, solo se abren cuando hacen falta para completar tu pedido y se borran justo después — consulta nuestros',
    credReassureLink: 'Términos del servicio',
    credReassureTail: 'para más detalles.',
    credBackupCodes: 'Códigos de respaldo',
    credBackupCodesFind: 'Cómo encontrar los códigos de respaldo',
    credBackupCodesHint: 'Tres códigos de ocho dígitos, tal y como los emite EA.',
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
    marketingOptIn:
      'Quiero recibir ofertas de GFS y avisos de rebajas de monedas. Opcional: las actualizaciones de tus pedidos llegan igualmente.',
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
    emailPrefsTitle: 'Preferencias de correo',
    emailPrefsOptIn: 'Quiero recibir ofertas de GFS y avisos de rebajas de monedas',
    emailPrefsNote:
      'Las actualizaciones de tus pedidos forman parte del servicio y se envían igualmente: '
      + 'esto solo controla el correo promocional.',
    emailPrefsSaved: 'Guardado.',
    emailPrefsFailed: 'No se pudo guardar. Inténtalo de nuevo en un momento.',
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
    boostPlatformHint: 'La cuenta en la que iniciará sesión el jugador.',
    boostPlatformPlayStation: 'PlayStation',
    boostPlatformPc: 'PC',
    boostPlatformNeeded: 'Elige una plataforma para continuar.',
    launcherLabel: '¿Con qué launcher juegas a FC?',
    launcherHint: 'En PC el inicio de sesión cambia según el launcher, así que necesitamos el correcto.',
    launcherPlaceholder: 'Elige tu launcher',
    launcherSteam: 'Steam',
    launcherEaApp: 'EA app',
    launcherEpic: 'Epic Games',
    launcherNeeded: 'Elige tu launcher para continuar.',
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
    tabDiscount: 'Descuento',
    tabRewards: 'Puntos',
    rewardsNoAccount: 'Inicia sesión para usar puntos en este pedido.',
    rewardsNoneYet: 'Aún no tienes puntos que gastar.',
    rewardsCapNote: (cap) => `Puedes pagar hasta un ${cap} del pedido con puntos.`,
    rewardsUseAll: 'Usar el máximo',
    rewardsClear: 'Quitar',
    and: 'y',
    consentLead: 'Entiendo y acepto los',
    consentTerms: 'Términos del servicio',
    consentPrivacy: 'Política de privacidad',
    consentAml: 'Política AML y KYC',
    howToFind: '¿Cómo encontrarlo?',
    couponLabel: 'Código de descuento',
    couponApply: 'Aplicar',
    couponHint: '¿Tienes un código? Introdúcelo aquí.',
    couponApplied: (code) => `${code} aplicado.`,
    pointsLabel: 'Puntos de recompensa',
    pointsHintUsable: (balance, usable) =>
      `Tienes ${balance}. Puedes usar hasta ${usable} aquí.`,
    pointsHintPlain: (balance) => `Tienes ${balance} puntos.`,
    createAccount: 'Crea una cuenta',
    createAccountRest: 'para ganar puntos con este pedido y gastarlos en el siguiente.',

    balanceTitle: 'Tu saldo de la cuenta',
    balancePoints: (points) => `${points} puntos de recompensa`,
    balanceWorth: (value) => `Equivalen a ${value} al pagar.`,
    balanceUsable: (points) => `Es el máximo que permite este pedido: ${points} puntos.`,
    balanceUse: (points) => `Usar ${points} puntos`,
    balanceApplied: (points) => `${points} puntos aplicados a este pedido.`,
    balanceRemove: 'Quitar',
    balanceNone: 'Aún no tienes puntos. Este pedido te dará algunos cuando termine tu periodo de garantía.',
    balanceTooSmall: 'Este pedido es demasiado pequeño para gastar puntos.',

    summaryTitle: 'Tu pedido',
    total: 'Total',
    earnsPoints: (points) =>
      `Ganarás unos ${points} puntos, cuando se cierre tu ventana de garantía.`,
    earnsPointsGuest: (points) =>
      `Inicia sesión para ganar ${points} puntos en este pedido — los pedidos como invitado no acumulan.`,
    continue: 'Continuar',
    priceNote:
      'Los precios se mantienen unos minutos y se vuelven a comprobar al pagar, así que lo ' +
      'que ves aquí es lo que se cobra.',
    refreshingPrice: 'Actualizando el precio…',
    priceHeld: (time) => `Precio reservado ${time}`,

    seoTitleFor: (service, season) => `Pedir ${service} — EA ${season}`,
    seoDescriptionGeneric: 'Todo con el precio por delante. Nada añadido al final.',
    cartTitle: 'Tu carrito',
    cartRemove: 'Quitar del carrito',
    continueShopping: 'Seguir comprando',
    deliveryInfoTitle: 'Información de entrega',
    deliveryInfoLead:
      'En este tipo de entrega, el trader inicia sesión en tu cuenta y te entrega las '
      + 'monedas, jugadores, sobres o SBC de FC 26.',
    deliveryInfoPacks:
      'Si compras sobres, te transferimos las monedas equivalentes y los abres tú.',
    deliveryInfoWait:
      'No tienes que hacer nada más que esperar a que llegue la transferencia.',
    deliveryCheck1:
      'El acceso al mercado de traspasos ya debe estar desbloqueado en la Web o Companion App.',
    deliveryCheck2:
      'No inicies sesión en la cuenta mientras el servicio está en marcha.',
    deliveryCheck3:
      'La lista de traspasos debe estar vacía y la cuenta necesita al menos 5.000 monedas.',
    deliveryCheck4: 'Ten a mano tus códigos de respaldo.',
    clickHere: 'Haz clic aquí',
    backupCodePlaceholder: 'Cada código tiene 8 dígitos',
    eaEmailPlaceholder: 'EA Web/Companion App',
    fixFieldsError: 'Revisa los campos marcados.',
    errEaEmail: '¡Introduce el correo de Origin (Web App)!',
    errEaPassword: '¡Introduce la contraseña de Origin (Web App)!',
    errEaPasswordShort: 'Parece demasiado corta: una contraseña de EA tiene al menos 8 caracteres.',
    errBackupCode: (n) => `¡Introduce el código de respaldo ${n}!`,
    errBackupCodeFormat: (n) => `El código de respaldo ${n} debe tener exactamente 8 dígitos.`,
    userInfoTitle: 'Tus datos',
    fullName: 'Nombre completo',
    fullNameHint: 'El nombre que aparecerá en tu recibo.',
    countryCode: 'Prefijo país',
    phoneHelper: 'Solo para incidencias urgentes del pedido — nunca te enviaremos spam.',
    discordLabel: 'Discord (opcional)',
    discordHelper: 'Podemos escribirte por Discord para resolver el pedido más rápido.',
    discordPlaceholder: 'tunombre',
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
      + 'credenciales, se cifran y se destruyen al terminar el pedido.',
    deliveryComfortHint:
      'Iniciamos sesión y comerciamos directamente. Más rápido para cantidades grandes; te ' +
      'pediremos tus credenciales al finalizar la compra.',
    deliveryAuction: 'Mercado de traspasos: tú listas, nosotros compramos',
    deliveryComfort: 'Comfort trade: entramos por ti',
    coachingNextTitle: 'Qué pasa ahora',
    coachingNextBody:
      'Tus sesiones aparecen en tu cuenta en cuanto se confirma el pago, y las reservas ' +
      'desde la página de clases cuando quieras. Para las clases nunca necesitamos tus ' +
      'credenciales de EA: juegas en tu propia cuenta mientras tu entrenador observa.',
    signInTitle: 'Sobre tus credenciales',
    signInBody:
      'Se cifran en cuanto nos llegan, solo ' +
      'las abre el trader que lleva tu pedido y se destruyen al terminar. Te recordaremos ' +
      'que cambies la contraseña después.',
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
    testingTitle: 'Los pagos están en pruebas',
    testingBody:
      'Nuestro sistema de pago aún se está afinando. Tomamos todas las precauciones y tu ' +
      'pedido se gestiona igual que siempre, pero si algo en esta pantalla parece incorrecto, ' +
      'detente y habla con nosotros antes de pagar.',
    testingContact: 'Escribir a soporte',
    stubTitle: 'La pasarela de pago no está configurada',
    stubBody:
      'Este entorno funciona sin credenciales de pago reales, así que no se moverá dinero. ' +
      'El pedido existe y es visible en la consola de operaciones. Configura las claves de ' +
      'Razorpay para habilitar pagos reales.',
    payWindowFailed: 'La ventana de pago no se ha abierto. Revisa tu conexión e inténtalo de nuevo.',
    trackOrder: 'Seguir este pedido',

    /* ---------------------------------------------------------- manual payment --- */
    signInToContinue: 'Inicia sesión para continuar',
    signInWhy: 'Los pedidos se hacen desde una cuenta para que tus puntos de recompensa lleguen a ella. Es cuestión de un momento.',
    payTitle: 'Paga tu pedido',
    payIntro:
      'Paga con cualquiera de las opciones siguientes y luego introduce la referencia que te dé ' +
      'tu aplicación de pago. La comprobamos en nuestra cuenta y empezamos tu pedido en cuanto llegue.',
    payTabUpi: 'UPI',
    payTabPaypal: 'PayPal',
    payTabCrypto: 'Cripto',
    payTabInternational: 'Internacional',
    payIntlBadge: 'Próximamente',
    payIntlTitle: 'Las opciones de pago internacional llegarán pronto',
    payIntlBody: (methods: string[]) =>
      `Por ahora, usa ${methods.join(' o ')} para los pedidos internacionales.`,
    payIntlNote: 'Aquí aparecerán más formas de pagar desde fuera de la India.',
    payIntlUse: (method: string) => `Pagar con ${method}`,
    payScanHint: 'Escanea el código con tu aplicación de pago, o copia la dirección de abajo.',
    payAmountDue: (total: string) => `Importe a enviar: ${total}`,
    payPayTo: 'Pagando a',
    payCopy: 'Copiar',
    payCopied: 'Copiado',
    payCopyFailed: 'No se ha podido copiar: selecciona la dirección y cópiala a mano.',
    payOpenPaypal: 'Abrir PayPal',
    payCryptoWarning:
      'Envía únicamente USDT por la red TRON (TRC20) a esta dirección. Enviar cualquier otro ' +
      'activo, o usar otra red, provocará la pérdida permanente de los fondos.',
    payProofLabel: 'Captura del pago',
    payProofHint: 'La pantalla de confirmación que muestra tu aplicación de pago tras enviarlo: un JPG, PNG o WebP de menos de 5 MB.',
    payProofRemove: 'Quitar',
    payProofPreviewAlt: 'La captura que has seleccionado',
    payProofWrongType: 'Ese archivo no es una imagen. Adjunta una captura en JPG, PNG o WebP.',
    payProofTooBig: 'Esa imagen supera los 5 MB. Una captura suele pesar mucho menos.',
    payProofFailed: 'Hemos registrado tu referencia, pero la captura no se ha subido. Vuelve a adjuntarla abajo.',
    payProofRequired: 'Adjunta una captura del pago antes de enviar.',
    payProofRetry: 'Subir captura',
    payReferenceLabel: 'Número de referencia UTR / de la transacción',
    payReferenceHint:
      'Cuando hayas completado el pago, introduce aquí el número de referencia para que podamos ' +
      'verificarlo y procesar tu pedido.',
    payReferencePlaceholder: (kind: string) => {
      if (kind === 'UTR') return 'Número UTR de 12 dígitos'
      if (kind === 'transaction hash') return 'Hash de la transacción (TXID)'
      return 'ID de la transacción'
    },
    payReferenceRequired: 'Introduce el número de referencia de tu pago antes de enviar.',
    paySubmit: 'Ya he pagado: enviar referencia',
    payChangeMethod: 'Pagar de otra forma',
    payClaimTitle: 'Referencia recibida: la estamos comprobando',
    payClaimBody: (reference: string) =>
      `Tenemos tu referencia ${reference}. Alguien la comprueba en nuestra cuenta y libera tu ` +
      'pedido en cuanto aparezca el pago. Te enviaremos un correo cuando ocurra.',
    payClaimResubmit: '¿La has escrito mal? Envía otra referencia',
    payClaimFailed: 'No hemos podido registrar esa referencia. Revísala e inténtalo de nuevo.',
    payMethodsFailed:
      'No hemos podido cargar las opciones de pago. Actualiza la página o escribe a soporte con ' +
      'la referencia de tu pedido.',
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

  coachingPage: {
    seoDescription: (season: string): string =>
      `Clases individuales de EA ${season} centradas en tu juego: vemos cómo juegas, detectamos ` +
      `lo que te cuesta partidos y te damos cambios que puedes aplicar. Sesión suelta o paquete de seis.`,
    eyebrow: (season: string): string => `${season} · Clases`,
    title: 'Mejora a propósito, no por casualidad',
    lead:
      'Clases individuales de EA FC centradas en tu juego, tus hábitos y lo que te está frenando. ' +
      'Analizamos lo que pasa en tus partidos y lo convertimos en cambios claros que puedes aplicar de verdad.',
    bookCta: 'Reservar una sesión',
    meetCta: 'Conoce al coach',
    badgeFeedback: 'Feedback personalizado',
    badgeImprovements: 'Mejoras prácticas',
    badgeConfidence: 'Juega con más confianza',
    heroAlt: 'Un jugador con auriculares analizando un partido en un monitor',
    singleBody:
      'Una sesión individual centrada en tu juego. Detecta los mayores problemas de tu juego y ' +
      'sal con ajustes claros en los que trabajar.',
    packBody:
      'Seis sesiones para trabajar tu juego con el tiempo. Úsalas a tu ritmo durante el mes y ' +
      'construye sobre cada sesión.',
    buySession: 'Comprar una sesión',
    buyPackage: 'Comprar el paquete',
    saveBadge: (pct: number): string => `Ahorra ${pct}%`,
    processEyebrow: 'Nuestro proceso',
    processTitle: '¿Qué pasa de verdad en una sesión?',
    processAside: 'Un proceso sencillo, centrado en tu mejora.',
    watchTitle: 'Observamos',
    watchBody: 'Vemos cómo juegas de verdad, no solo el resultado final.',
    findTitle: 'Detectamos',
    findBody: 'Las decisiones, hábitos y patrones que te cuestan partidos.',
    fixTitle: 'Corregimos',
    fixBody: 'Recibes cambios prácticos en tu juego, tu táctica y tu toma de decisiones.',
    applyTitle: 'Tú aplicas',
    applyBody: 'Sal con algo que puedas usar de verdad en tus próximos partidos.',
    coachEyebrow: 'Conoce a tu coach',
    coachTitleLine: 'Coach de EA FC · Juego y FUT Champions',
    coachBio:
      'Más de 5 años de experiencia competitiva. Desde regates y patrones de ataque hasta defensa, ' +
      'toma de decisiones y mentalidad: desarrolla el juego completo que necesitas para competir al máximo.',
    specialtiesLabel: 'Especialidades',
    specialties: ['Ataque', 'Defensa', 'Toma de decisiones', 'FUT Champions', 'Táctica'],
    languagesLabel: 'Idiomas:',
    languagesValue: 'Inglés, hindi',
    coachQuote: 'Mi objetivo es sencillo: ayudarte a entender tu juego y a progresar de forma constante.',
    viewProfile: 'Reserva tu sesión',
    coachPhotoAlt: 'Vinay, coach de EA FC',
    areasEyebrow: '¿En qué podemos trabajar?',
    areasTitle: 'Aspectos que podemos ayudarte a mejorar',
    areasAside: 'Tanto si empiezas como si compites, las clases se adaptan a tus objetivos.',
    areas: [
      { key: 'attacking', title: 'Ataque', body: 'Crea mejores ocasiones y toma mejores decisiones en el último tercio.' },
      { key: 'defending', title: 'Defensa', body: 'Mejora el posicionamiento, los cambios de jugador y las decisiones defensivas.' },
      { key: 'buildup', title: 'Salida de balón', body: 'Entiende cuándo avanzar, recircular la posesión o cambiar el ritmo.' },
      { key: 'tactics', title: 'Táctica', body: 'Monta un sistema según tu estilo en lugar de seguir a ciegas la última moda.' },
      { key: 'decisions', title: 'Toma de decisiones', body: 'Reduce errores innecesarios y elige mejor bajo presión.' },
      { key: 'champs', title: 'FUT Champions', body: 'Gana en regularidad y saca más partido a tus buenas actuaciones.' },
    ],
    reviewsEyebrow: 'En sus palabras',
    reviewsTitle: 'Lo que dijeron nuestros clientes',
    ctaTitle1: 'El objetivo no es jugar más partidos.',
    ctaTitle2: 'Es jugar mejores partidos.',
    ctaBody:
      'Jugar otros 100 partidos no corrige necesariamente los mismos errores. Descubre qué te está frenando de verdad.',
    ctaButton: 'Reserva tu sesión',
    tagline: 'Juega con cabeza. Mejora a propósito.',
  },
  boostingCheckout: {
    seoTitle: 'Pago del boosting',
    secureCheckout: 'Pago seguro',
    secureLead: 'Completa tu compra para empezar',
    stepDetails: 'Datos',
    stepPayment: 'Pago',
    stepConfirmation: 'Confirmación',

    signInTitle: 'Inicia sesión para hacer este pedido',
    signInBody: 'Los pedidos de boosting se hacen desde una cuenta, así el pedido y tus puntos se quedan contigo.',
    signInCta: 'Inicia sesión para continuar',

    platformTitle: 'Elige la plataforma',
    platformLead: 'Elige la plataforma en la que juegas para este servicio.',
    playstation: 'PlayStation',
    playstationSub: 'Juegas en PlayStation',
    pc: 'PC',
    pcSub: 'Elige tu plataforma de PC',
    psNote: 'En PlayStation no hace falta elegir nada más.',
    pcTitle: 'Elige la plataforma de PC',
    pcLead: 'Elige la plataforma a la que está vinculada tu cuenta de EA FC.',
    steam: 'Steam',
    steamSub: 'Juegas en Steam',
    eaApp: 'EA App',
    eaAppSub: 'Juegas en EA App',
    epic: 'Epic Games',
    epicSub: 'Juegas en Epic Games',
    pcNote: 'Asegúrate de elegir la plataforma correcta. Así procesamos tu pedido más rápido.',
    needPlatform: 'Elige una plataforma para continuar.',
    needLauncher: 'Elige dónde juegas en PC para continuar.',

    discordDetailsTitle: 'Datos de la cuenta por Discord',
    discordDetailsLead: 'Por tu seguridad, no pedimos los datos de acceso de EA en la web.',
    discordDetailsCallout: 'Después del pago te pondremos en contacto con nuestro equipo en Discord.',
    discordDetailsPoint1: 'Tus datos de EA solo se piden en tu ticket privado de Discord.',
    discordDetailsPoint2: 'Nuestro equipo te guía paso a paso.',
    discordDetailsPoint3: 'Así tu cuenta está más segura.',
    afterPaymentNote: 'Haz el pedido para continuar. Tras el pago te llevamos a Discord para terminar de prepararlo.',
    ticketCreated: '¡Tu ticket está creado!',

    continueToPayment: 'Continuar al pago',
    termsLead: 'Al continuar aceptas nuestros',
    termsTerms: 'Términos del servicio',
    termsPrivacy: 'Política de privacidad',
    termsAnd: 'y',
    termsAml: 'Política AML y KYC',

    orderTitle: 'Tu pedido',
    edit: 'Editar',
    couponPlaceholder: 'Introduce un código',
    apply: 'Aplicar',
    rewardsTitle: 'Recompensas',
    total: 'Total',
    trustSecureTitle: 'Pago seguro',
    trustSecureBody: 'Tus datos están a salvo con nosotros',
    trustFastTitle: 'Proceso rápido',
    trustFastBody: 'Empezamos enseguida tras el pago',
    trustSupportTitle: 'Soporte 24/7',
    trustSupportBody: 'Estamos aquí para ayudarte',

    payTitle: '¿Cómo quieres pagar?',
    payOnlineTitle: 'Pagar online',
    payOnlineBody: 'Tarjeta, UPI o banca online a través de la pasarela.',
    payUpiTitle: 'UPI',
    payUpiBody: 'Escanea el código y envíanos la referencia.',
    payPaypalTitle: 'PayPal',
    payPaypalBody: 'Haz el pago y comparte el id de la transacción.',
    payCryptoTitle: 'Cripto',
    payCryptoBody: 'Envía a la dirección indicada y comparte el hash.',

    processingTitle: 'Confirmando tu pago',
    processingBody: 'Tarda unos segundos. No cierres esta página.',

    confirmedTitle: '¡Pedido confirmado!',
    confirmedBody: 'Tu pago se ha completado.',
    confirmedEmailed: 'Te hemos enviado los detalles por correo.',
    confirmedLead:
      'Nuestro equipo ya está avisado. Únete a nuestro Discord para hablar con quien lleva '
      + 'tu pedido y seguir su avance.',
    submittedTitle: 'Pago enviado',
    submittedBody: 'Lo estamos comprobando en nuestra cuenta.',
    submittedLead:
      'Tu pedido está hecho y nuestro equipo ya está avisado. Confirmamos el pago a mano, '
      + 'normalmente en minutos, y esta página se actualiza sola cuando llega. Únete a '
      + 'nuestro Discord para hablar con quien lleva tu pedido.',
    joinDiscord: 'Únete a nuestro Discord',
    joinCaption: 'Pulsa el botón para entrar; nuestro equipo atenderá allí tu pedido.',
    orderId: 'Nº de pedido',
    service: 'Servicio',
    platformLabel: 'Plataforma',
    amountPaid: 'Importe pagado',
    amount: 'Importe',
    statusVerifying: 'Comprobando',
    needHelp: '¿Necesitas ayuda?',
    joinOurDiscord: 'Únete a nuestro Discord',
    orWord: 'o',
    contactSupport: 'contacta con soporte',

    loadFailed: 'No hemos podido cargar ese pedido. Revisa el enlace o contacta con soporte.',
    placeFailed: 'No hemos podido crear el pedido. Inténtalo de nuevo o contacta con soporte.',
  },

  coachingBook: {
    seoTitle: 'Reservar clases',
    back: 'Atrás',
    stepService: 'Servicio',
    stepDetails: 'Datos',
    stepPayment: 'Pago',
    chooseTitle: 'Elige tu opción de clases',
    chooseLead: 'Elige la opción que encaje con tus objetivos. Siempre puedes ampliar más adelante.',
    singleBullets: ['Sesión individual en directo', 'Análisis de tu juego', 'Consejos personalizados', 'Puntos de acción claros'],
    packBullets: ['Úsalas a tu ritmo', 'Seguimiento del progreso', 'Horarios flexibles', 'Mejor precio'],
    saveBadge: (pct: number): string => `Ahorra ${pct}%`,
    unsureTitle: '¿No sabes qué opción elegir?',
    unsureBody: 'Si dudas, empieza con una sesión suelta. Siempre puedes comprar el paquete más tarde.',
    continue: 'Continuar',
    signInToContinue: 'Inicia sesión para continuar',
    signInWhy: 'Tus clases quedan vinculadas a tu cuenta, así tus sesiones y puntos no se pierden.',
    detailsTitle: 'Cuéntanos un poco sobre ti',
    detailsLead: 'Esto nos ayuda a preparar tu sesión.',
    handleLabel: 'ID de EA FC / PSN / Xbox',
    handlePlaceholder: 'Escribe tu ID de juego (p. ej. VinayFC10)',
    handleRequired: 'Escribe tu ID de juego para que tu coach pueda encontrarte.',
    platformLabel: 'Plataforma',
    platformRequired: 'Elige la plataforma en la que juegas.',
    platformPlayStation: 'PlayStation',
    platformXbox: 'Xbox',
    platformPc: 'PC (EA App)',
    rankLabel: 'Rango / División actual',
    rankPlaceholder: 'Elige tu rango actual',
    rankDivision: (n: number): string => `División ${n}`,
    rankElite: 'División Élite',
    rankNotSure: 'No lo sé',
    focusLabel: '¿Qué quieres mejorar?',
    focusPlaceholder: 'p. ej. defensa, ataque, táctica, toma de decisiones...',
    optional: '(Opcional)',
    reviewTitle: 'Revisa tu pedido',
    coachingSession: 'Sesión de clases',
    summaryPlatform: 'Plataforma',
    summaryHandle: 'ID de juego',
    summaryRank: 'Rango actual',
    summaryFocus: 'Aspecto a mejorar',
    orderSummary: 'Resumen del pedido',
    total: 'Total',
    paymentMethod: 'Método de pago',
    recommended: 'Recomendado',
    payOnlineTitle: 'Pagar en línea',
    payOnlineBody: 'UPI, tarjeta de crédito o débito y banca en línea',
    payUpiTitle: 'UPI',
    payUpiBody: 'Escanea y paga desde cualquier app de UPI',
    payPaypalTitle: 'PayPal',
    payPaypalBody: 'Para pagos desde fuera de la India',
    payCryptoTitle: 'Cripto',
    payCryptoBody: 'USDT en la red TRON',
    acceptPrefix: 'Acepto los',
    terms: 'Términos del servicio',
    and: 'y la',
    privacy: 'Política de privacidad',
    acceptRequired: 'Acepta los términos para continuar.',
    payNow: 'Pagar ahora',
    payCaptionOnline: 'Pago seguro gestionado por nuestro proveedor de pagos',
    payCaptionManual: 'En la siguiente pantalla verás a dónde enviar el pago.',
    trustTitle: 'Tu información está segura',
    trustEncrypted: 'Enviada por una conexión cifrada',
    trustPurpose: 'Solo se usa para preparar y dar tus clases',
    trustPayments: 'Nunca vemos los datos de tu tarjeta ni tu acceso bancario',
    placeFailed: 'No hemos podido realizar el pedido. Inténtalo de nuevo.',
    loadOrderFailed: 'No hemos podido cargar ese pedido.',
    processingTitle: 'Procesando tu pago...',
    processingBody: 'Solo tardará unos segundos.',
    stageSecuring: 'Asegurando el pago',
    stageVerifying: 'Verificando la transacción',
    stageFinalising: 'Finalizando el pedido',
    doNotClose: 'No cierres esta página mientras confirmamos tu pago.',
    processingSlow:
      'Está tardando más de lo habitual. Tu pago está a salvo: esta página avanzará en cuanto se confirme.',
    successTitle: '¡Pago completado!',
    successBody: 'Tu sesión de clases está confirmada.',
    submittedTitle: 'Pago enviado',
    submittedBody: 'Lo estamos comprobando en nuestra cuenta. Esta página se actualizará sola en cuanto se confirme.',
    orderNumber: 'Número de pedido',
    service: 'Servicio',
    amount: 'Importe',
    date: 'Fecha',
    status: 'Estado',
    statusConfirmed: 'Confirmado',
    statusVerifying: 'Verificando el pago',
    emailSent: (email: string): string => `Te hemos enviado un correo de confirmación a ${email}.`,
    emailWhenConfirmed: (email: string): string => `Te escribiremos a ${email} en cuanto se confirme.`,
    discordTitle: 'Únete a nuestro Discord',
    discordBody: 'Tus próximos pasos y los detalles de la sesión se compartirán en Discord.',
    discordSteps: [
      'Pulsa el botón de abajo para unirte a nuestro servidor',
      'Verifica tu cuenta',
      'Tendrás acceso al canal de clases',
      'Nuestro equipo se pondrá en contacto contigo con más instrucciones',
      'Comparte tus partidas si te lo pedimos',
    ],
    joinDiscord: 'Unirse al servidor de Discord',
    discordEmailed: 'También te hemos enviado este enlace por correo.',
    discordEmailLater: 'También te enviaremos este enlace por correo en cuanto se confirme tu pago.',
    allSetTitle: '¡Todo listo!',
    allSetLead: 'Hora de mejorar.',
    allSetPaid: 'Pago completado',
    allSetVerifying: 'Pago enviado: lo confirmaremos en breve',
    allSetDiscord: 'Acceso a Discord facilitado',
    allSetGuide: 'Nuestro equipo te guiará a partir de aquí',
    allSetReady: 'Prepárate para tu sesión de clases',
    quote: 'Juega con cabeza. Mejora a propósito.',
    viewAccount: 'Ver mi cuenta',
    backToCoaching: 'Volver a clases',
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
    policyLineBoth: (single, block, hours) =>
      `${single} minutos suelta, ${block} minutos en pack · puedes moverla hasta ${hours}h antes`,
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
      TRADING_SERVICE: 'Comprar monedas',
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
    coins: (value: string, unit: 'K' | 'M'): string => `${value} ${unit}`,
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
