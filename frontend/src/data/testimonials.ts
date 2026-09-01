/**
 * Customer testimonials.
 *
 * <p><b>These are real, and that is the only reason they exist.</b> A storefront
 * that invents its own reviews is committing the thing it is trying to look
 * innocent of, and in most of the markets this site sells into — India's CCPA
 * dark-pattern rules, the UK's DMCC Act, the US FTC's endorsement guides — fake
 * reviews are specifically illegal, not merely tacky. Every quote below was
 * supplied by the business from real customers.
 *
 * <p><b>Quotes are translated, and always say so.</b> A wall of English on a French
 * page is its own failure, so each quote carries a reviewed Spanish and French
 * version. What must never happen is the swap being silent: presenting our wording
 * as the sentence a customer wrote is putting words in their mouth, and under the
 * FTC's endorsement guides and the UK DMCC Act a testimonial has to be what the
 * endorser actually said. So a translated card is labelled as translated and the
 * original is one click away — the same contract Booking.com and Trustpilot keep.
 *
 * <p>The English text is the record. Translations carry the numbers, ranks and win
 * counts across exactly and add nothing: no softening, no sharpening, no polish the
 * customer did not write. A locale with no translation falls back to English, which
 * is honest rather than broken.
 *
 * <p>Deliberately absent: star ratings, avatars, dates, order values. None of
 * those were supplied, and manufacturing them to make the section look fuller is
 * the same offence as manufacturing the quote.
 */

export type TestimonialService = 'trading' | 'boosting' | 'coaching'

export type Testimonial = {
  /** First name as given. No surnames — these are customers, not endorsers. */
  name: string
  /** ISO 3166-1 alpha-2. Rendered as a letter code, never as a flag emoji. */
  country: string
  service: TestimonialService
  /** The customer's own words, in English. This is the record. */
  quote: string
  /**
   * Reviewed translations, by locale.
   *
   * <p>Typed as a narrow literal union rather than against the i18n `Language` type,
   * so this stays a plain data file with no imports. A locale that is missing here
   * renders the English original — the fallback is deliberate, not an oversight.
   */
  translated?: Partial<Record<'es' | 'fr', string>>
}

export const TESTIMONIALS: Testimonial[] = [
  /* ------------------------------------------------------------- trading --- */
  {
    name: 'Santosh',
    country: 'IN',
    service: 'trading',
    quote:
      'First time buying coins from GFS and honestly the process was super smooth. '
      + 'I ordered 2.4M coins during the TOTS promo. I received the coins without any '
      + 'issue. Definitely a good experience.',
    translated: {
      es:
        'Primera vez que compro monedas en GFS y la verdad es que el proceso fue muy sencillo. Pedí 2,4 M de monedas durante la promo TOTS. Recibí las monedas sin ningún problema. Sin duda una buena experiencia.',
      fr:
        'Premier achat de coins chez GFS et honnêtement le processus a été très simple. J’ai commandé 2,4 M de coins pendant la promo TOTS. J’ai reçu les coins sans aucun souci. Vraiment une bonne expérience.',
    },
  },
  {
    name: 'Karan',
    country: 'IN',
    service: 'trading',
    quote:
      'I’ve been using GFS for around a month now and every order has been handled '
      + 'really well. I ordered 850K coins during the Futties promo. Fast delivery, '
      + 'good communication and no unnecessary hassle.',
    translated: {
      es:
        'Llevo alrededor de un mes usando GFS y todos los pedidos se han gestionado muy bien. Pedí 850 K monedas durante la promo Futties. Entrega rápida, buena comunicación y sin complicaciones innecesarias.',
      fr:
        'J’utilise GFS depuis environ un mois et toutes mes commandes ont été très bien gérées. J’ai commandé 850 K coins pendant la promo Futties. Livraison rapide, bonne communication et aucune complication inutile.',
    },
  },
  {
    name: 'Anmol',
    country: 'IN',
    service: 'trading',
    quote:
      'Bought 5M coins in one go during the TOTY promo and everything went perfectly. '
      + 'I was a little nervous because it was a big order, but the transfer was '
      + 'completed smoothly. Will definitely order again.',
    translated: {
      es:
        'Compré 5 M de monedas de una sola vez durante la promo TOTY y todo salió perfecto. Estaba un poco nervioso porque era un pedido grande, pero la transferencia se completó sin problemas. Volveré a pedir sin duda.',
      fr:
        'J’ai acheté 5 M de coins en une seule fois pendant la promo TOTY et tout s’est parfaitement passé. J’étais un peu nerveux parce que c’était une grosse commande, mais le transfert s’est déroulé sans accroc. Je recommanderai sans hésiter.',
    },
  },

  {
    name: 'Rohit',
    country: 'IN',
    service: 'trading',
    quote:
      'Second order with GFS and once again everything was quick and straightforward. '
      + 'I picked up 1.6M coins during the TOTS promo. Prices were good and the whole '
      + 'process took much less time than I expected.',
    translated: {
      es:
        'Segundo pedido con GFS y otra vez todo fue rápido y sencillo. CogÃ­ 1,6 M de monedas durante la promo TOTS. Los precios estaban bien y todo el proceso llevó mucho menos tiempo del que esperaba.',
      fr:
        'Deuxième commande chez GFS et une fois de plus tout a été rapide et simple. J’ai pris 1,6 M de coins pendant la promo TOTS. Les prix étaient bons et l’ensemble a pris bien moins de temps que prévu.',
    },
  },
  {
    name: 'Arjun',
    country: 'IN',
    service: 'trading',
    quote:
      'Really easy experience for a first-time buyer. I ordered 720K coins during the '
      + 'Futties promo, and support replied quickly and guided me through the whole '
      + 'process. Coins received without any problems.',
    translated: {
      es:
        'Una experiencia muy fácil para ser mi primera compra. Pedí 720 K monedas durante la promo Futties, y soporte respondió rápido y me guió durante todo el proceso. Recibí las monedas sin ningún problema.',
      fr:
        'Expérience vraiment simple pour un premier achat. J’ai commandé 720 K coins pendant la promo Futties, et le support a répondu vite et m’a guidé tout du long. Coins reçus sans le moindre problème.',
    },
  },
  {
    name: 'Brown',
    country: 'GB',
    service: 'trading',
    quote:
      'Found GFS through the FC community and decided to try them for the first time. '
      + 'The 2.8M-coin TOTY promo order was handled very smoothly, and the coins were '
      + 'delivered exactly as expected. Great service.',
    translated: {
      es:
        'Encontré GFS a través de la comunidad de FC y decidí probarlos por primera vez. El pedido de 2,8 M de monedas en la promo TOTY se gestionó muy bien, y las monedas llegaron exactamente como esperaba. Gran servicio.',
      fr:
        'J’ai découvert GFS via la communauté FC et j’ai décidé de les essayer pour la première fois. La commande de 2,8 M de coins pendant la promo TOTY a été gérée très proprement, et les coins ont été livrés exactement comme prévu. Excellent service.',
    },
  },
  {
    name: 'Ahmed',
    country: 'AE',
    service: 'trading',
    quote:
      'Ordered 1.2M coins during the TOTS promo and was impressed with how simple the '
      + 'process was. Communication was clear and delivery was fast. Would definitely '
      + 'use GFS again.',
    translated: {
      es:
        'Pedí 1,2 M de monedas durante la promo TOTS y me impresionó lo sencillo que fue el proceso. La comunicación fue clara y la entrega rápida. Sin duda volvería a usar GFS.',
      fr:
        'J’ai commandé 1,2 M de coins pendant la promo TOTS et j’ai été impressionné par la simplicité du processus. La communication était claire et la livraison rapide. Je referais appel à GFS sans hésiter.',
    },
  },
  {
    name: 'Marco',
    country: 'IT',
    service: 'trading',
    quote:
      'I’ve placed multiple orders with GFS and the service has been consistent every '
      + 'time. I ordered 3.5M coins during the Futties promo. Quick responses, smooth '
      + 'delivery and no unnecessary complications. Very happy with the experience.',
    translated: {
      es:
        'He hecho varios pedidos con GFS y el servicio ha sido constante siempre. Pedí 3,5 M de monedas durante la promo Futties. Respuestas rápidas, entrega fluida y sin complicaciones innecesarias. Muy contento con la experiencia.',
      fr:
        'J’ai passé plusieurs commandes chez GFS et le service a été constant à chaque fois. J’ai commandé 3,5 M de coins pendant la promo Futties. Réponses rapides, livraison fluide et aucune complication inutile. Très satisfait.',
    },
  },

  /* ------------------------------------------------------------ boosting --- */
  {
    name: 'Rohan',
    country: 'IN',
    service: 'boosting',
    quote:
      'Used GFS for the first time during my recent Champs run and ordered 14 wins. '
      + 'Everything was completed exactly as requested, with smooth communication from '
      + 'start to finish. Really happy with the service.',
    translated: {
      es:
        'Usé GFS por primera vez en mi última racha de Champs y pedí 14 victorias. Todo se completó exactamente como lo pedí, con una comunicación fluida de principio a fin. Muy contento con el servicio.',
      fr:
        'J’ai utilisé GFS pour la première fois lors de ma dernière session de Champs et j’ai commandé 14 victoires. Tout a été réalisé exactement comme demandé, avec une communication fluide du début à la fin. Vraiment satisfait du service.',
    },
  },
  {
    name: 'Adam',
    country: 'GB',
    service: 'boosting',
    quote:
      'I’ve placed a few Champs orders with GFS now, including a Rank 1 finish. The '
      + 'service has been reliable each time, and the order was completed exactly as '
      + 'promised. The communication was also clear throughout.',
    translated: {
      es:
        'Ya he hecho varios pedidos de Champs con GFS, incluido un puesto de Rango 1. El servicio ha sido fiable cada vez y el pedido se completó exactamente como se prometió. La comunicación también fue clara en todo momento.',
      fr:
        'J’ai déjà passé plusieurs commandes Champs avec GFS, dont une finition Rang 1. Le service a été fiable à chaque fois et la commande a été réalisée exactement comme promis. La communication a également été claire du début à la fin.',
    },
  },
  {
    name: 'Yash',
    country: 'IN',
    service: 'boosting',
    quote:
      'Ordered 13 wins with GFS and received exactly what I requested. I’ve since used '
      + 'the service again and had another smooth experience. The results have been '
      + 'consistent, and the process was easy from start to finish.',
    translated: {
      es:
        'Pedí 13 victorias con GFS y recibí exactamente lo que había pedido. Desde entonces he vuelto a usar el servicio y la experiencia fue igual de buena. Los resultados han sido constantes y el proceso fue fácil de principio a fin.',
      fr:
        'J’ai commandé 13 victoires chez GFS et j’ai reçu exactement ce que j’avais demandé. J’ai depuis réutilisé le service et l’expérience a été tout aussi fluide. Les résultats sont constants et le processus est simple du début à la fin.',
    },
  },

  {
    name: 'Lucas',
    country: 'FR',
    service: 'boosting',
    quote:
      'GFS handled my recent 14-win Champs order without any issues. The order was '
      + 'completed as requested, and I was kept updated during the process. Everything '
      + 'felt professional and straightforward.',
    translated: {
      es:
        'GFS gestionó mi último pedido de 14 victorias en Champs sin ningún problema. Se completó tal y como lo pedí y me mantuvieron informado durante el proceso. Todo resultó profesional y directo.',
      fr:
        'GFS a géré ma dernière commande de 14 victoires en Champs sans le moindre souci. La commande a été réalisée comme demandé et j’ai été tenu au courant tout du long. Tout a paru professionnel et clair.',
    },
  },
  {
    name: 'Harsh',
    country: 'IN',
    service: 'boosting',
    quote:
      'I’ve been using GFS for over 8 months, mainly for Champs services. My latest '
      + 'order was for 15 wins, and they delivered Rank 1 exactly as promised. I’ve '
      + 'never had any major issues with their service.',
    translated: {
      es:
        'Llevo más de 8 meses usando GFS, sobre todo para servicios de Champs. Mi último pedido fue de 15 victorias y entregaron Rango 1 exactamente como prometieron. Nunca he tenido ningún problema serio con su servicio.',
      fr:
        'J’utilise GFS depuis plus de 8 mois, surtout pour les services Champs. Ma dernière commande portait sur 15 victoires et ils ont livré le Rang 1 exactement comme promis. Je n’ai jamais eu de vrai problème avec leur service.',
    },
  },
  {
    name: 'Ethan',
    country: 'CA',
    service: 'boosting',
    quote:
      'I’ve used GFS for more than a year and have placed several FUT Champs orders '
      + 'with different win targets. The results have consistently matched what I '
      + 'ordered, and the service has always been reliable.',
    translated: {
      es:
        'Llevo más de un año usando GFS y he hecho varios pedidos de FUT Champs con distintos objetivos de victorias. Los resultados siempre han coincidido con lo que pedí y el servicio siempre ha sido fiable.',
      fr:
        'J’utilise GFS depuis plus d’un an et j’ai passé plusieurs commandes FUT Champs avec des objectifs de victoires différents. Les résultats ont toujours correspondu à ce que j’avais commandé, et le service a toujours été fiable.',
    },
  },
  {
    name: 'Sameer',
    country: 'IN',
    service: 'boosting',
    quote:
      'I’ve been using GFS for almost 2 years and have ordered their Champs service '
      + 'many times. My latest order was for 14 wins, and it was completed exactly as '
      + 'requested. The consistency and communication are the main reasons I keep '
      + 'coming back.',
    translated: {
      es:
        'Llevo casi 2 años usando GFS y he pedido su servicio de Champs muchas veces. Mi último pedido fue de 14 victorias y se completó exactamente como lo pedí. La constancia y la comunicación son las principales razones por las que sigo volviendo.',
      fr:
        'J’utilise GFS depuis presque 2 ans et j’ai commandé leur service Champs de nombreuses fois. Ma dernière commande portait sur 14 victoires et elle a été réalisée exactement comme demandé. La régularité et la communication sont les principales raisons pour lesquelles je reviens.',
    },
  },
  {
    name: 'Omar',
    country: 'SA',
    service: 'boosting',
    quote:
      'I’ve been using GFS for around a year and have ordered several Champs services '
      + 'during that time. My latest Rank 1 order was completed exactly as promised, '
      + 'just like my previous orders. Overall, a very reliable service.',
    translated: {
      es:
        'Llevo alrededor de un año usando GFS y he pedido varios servicios de Champs en ese tiempo. Mi último pedido de Rango 1 se completó exactamente como prometieron, igual que los anteriores. En general, un servicio muy fiable.',
      fr:
        'J’utilise GFS depuis environ un an et j’ai commandé plusieurs services Champs sur cette période. Ma dernière commande Rang 1 a été réalisée exactement comme promis, comme les précédentes. Dans l’ensemble, un service très fiable.',
    },
  },

  /* ------------------------------------------------------------ coaching --- */
  {
    name: 'Manish',
    country: 'IN',
    service: 'coaching',
    quote:
      'I was usually getting around 8 wins in Champs and couldn’t understand how to '
      + 'push further. After working on my defending, attacking and decision making '
      + 'through the coaching package, I started getting 11–12 wins consistently. The '
      + 'biggest difference was actually understanding my mistakes.',
    translated: {
      es:
        'Normalmente conseguía unas 8 victorias en Champs y no entendía cómo pasar de ahí. Después de trabajar la defensa, el ataque y la toma de decisiones con el paquete de clases, empecé a lograr 11–12 victorias de forma constante. La mayor diferencia fue entender de verdad mis errores.',
      fr:
        'J’obtenais en général environ 8 victoires en Champs et je ne comprenais pas comment aller plus loin. Après avoir travaillé ma défense, mon attaque et mes prises de décision avec le pack de coaching, j’ai commencé à faire 11–12 victoires régulièrement. La plus grosse différence a été de vraiment comprendre mes erreurs.',
    },
  },
  {
    name: 'Deepak',
    country: 'IN',
    service: 'coaching',
    quote:
      'I started around Division 7 and was struggling to move up. After completing the '
      + 'coaching package, I managed to reach Division 3 for the first time. We worked a '
      + 'lot on my core mechanics and gameplay decisions, which completely changed how I '
      + 'approached matches.',
    translated: {
      es:
        'Empecé sobre la División 7 y me costaba subir. Después de completar el paquete de clases, conseguí llegar a la División 3 por primera vez. Trabajamos mucho la mecánica básica y las decisiones de juego, lo que cambió por completo cómo afronto los partidos.',
      fr:
        'J’ai commencé autour de la Division 7 et j’avais du mal à progresser. Après avoir terminé le pack de coaching, j’ai réussi à atteindre la Division 3 pour la première fois. On a beaucoup travaillé mes mécaniques de base et mes décisions de jeu, ce qui a complètement changé ma façon d’aborder les matchs.',
    },
  },
  {
    name: 'Saurabh',
    country: 'IN',
    service: 'coaching',
    quote:
      'My Champs results were usually around 10–11 wins and I could never consistently '
      + 'push higher. After the coaching, I started getting 12 wins regularly and '
      + 'eventually reached 13 wins. The sessions helped me fix the small mistakes that '
      + 'were holding me back.',
    translated: {
      es:
        'Mis resultados en Champs rondaban las 10–11 victorias y nunca conseguía subir de forma constante. Después de las clases, empecé a hacer 12 victorias con regularidad y acabé llegando a 13. Las sesiones me ayudaron a corregir los pequeños errores que me estaban frenando.',
      fr:
        'Mes résultats en Champs tournaient autour de 10–11 victoires et je n’arrivais jamais à monter durablement. Après le coaching, j’ai commencé à faire 12 victoires régulièrement et j’ai fini par atteindre 13. Les séances m’ont aidé à corriger les petites erreurs qui me bloquaient.',
    },
  },
  {
    name: 'Leo',
    country: 'IT',
    service: 'coaching',
    quote:
      'I started around Division 5 and wanted to improve my actual gameplay instead of '
      + 'just copying tactics online. After the coaching package, I managed to reach '
      + 'Division 2 for the first time. The 1-on-1 guidance made it much easier to '
      + 'understand exactly what I needed to improve.',
    translated: {
      es:
        'Empecé sobre la División 5 y quería mejorar mi juego de verdad en lugar de copiar tácticas de internet. Después del paquete de clases, conseguí llegar a la División 2 por primera vez. La orientación individual hizo mucho más fácil entender exactamente qué tenía que mejorar.',
      fr:
        'J’ai commencé autour de la Division 5 et je voulais vraiment améliorer mon jeu plutôt que de copier des tactiques trouvées en ligne. Après le pack de coaching, j’ai réussi à atteindre la Division 2 pour la première fois. L’accompagnement individuel a rendu bien plus clair ce que je devais améliorer.',
    },
  },
  {
    name: 'Nitesh',
    country: 'IN',
    service: 'coaching',
    quote:
      'I had reached Division 2 before but could never break into Division 1. After '
      + 'working on my defensive mechanics, player switching and decision making, I '
      + 'finally hit Division 1 for the first time. Definitely one of my biggest '
      + 'improvements in FC.',
    translated: {
      es:
        'Ya había llegado a la División 2, pero nunca conseguía entrar en la División 1. Después de trabajar la mecánica defensiva, el cambio de jugador y la toma de decisiones, por fin llegué a División 1 por primera vez. Sin duda una de mis mayores mejoras en FC.',
      fr:
        'J’avais déjà atteint la Division 2 mais je n’arrivais jamais à passer en Division 1. Après avoir travaillé mes mécaniques défensives, le changement de joueur et mes prises de décision, j’ai enfin atteint la Division 1 pour la première fois. Sans doute l’une de mes plus grandes progressions sur FC.',
    },
  },
  {
    name: 'Chris',
    country: 'AU',
    service: 'coaching',
    quote:
      'I had never reached 13 wins in Champs before. After working specifically on my '
      + 'attacking patterns, defending and gameplay awareness, I finally managed to hit '
      + '13 wins. The coaching was focused on my actual gameplay instead of generic '
      + 'advice.',
    translated: {
      es:
        'Nunca había llegado a 13 victorias en Champs. Después de trabajar específicamente mis patrones de ataque, la defensa y la lectura del juego, por fin conseguí llegar a 13 victorias. Las clases se centraron en mi juego real en lugar de dar consejos genéricos.',
      fr:
        'Je n’avais jamais atteint 13 victoires en Champs. Après avoir travaillé spécifiquement mes schémas offensifs, ma défense et ma lecture du jeu, j’ai enfin réussi à faire 13 victoires. Le coaching portait sur mon jeu réel plutôt que sur des conseils génériques.',
    },
  },
  {
    name: 'Harsh',
    country: 'IN',
    service: 'coaching',
    quote:
      'I booked a single coaching session before my Champs run because I wanted to '
      + 'understand what was stopping me from improving. We went straight into my '
      + 'gameplay, identified the biggest problems and worked on them. That weekend I hit '
      + '15 wins in Champs for the first time.',
    translated: {
      es:
        'Reservé una sola sesión de clases antes de mi Champs porque quería entender qué me impedía mejorar. Fuimos directos a mi juego, identificamos los mayores problemas y los trabajamos. Ese fin de semana llegué a 15 victorias en Champs por primera vez.',
      fr:
        'J’ai réservé une seule séance de coaching avant ma session de Champs parce que je voulais comprendre ce qui m’empêchait de progresser. On est allés droit à mon jeu, on a identifié les plus gros problèmes et on les a travaillés. Ce week-end-là, j’ai atteint 15 victoires en Champs pour la première fois.',
    },
  },
  {
    name: 'Abhishek',
    country: 'IN',
    service: 'coaching',
    quote:
      'I was stuck around 10–11 wins and decided to book a single coaching session. We '
      + 'went through my gameplay and fixed some small but important mistakes in my '
      + 'defending and decision making. I reached 13 wins in my next Champs run, which '
      + 'was a new personal best.',
    translated: {
      es:
        'Estaba estancado en 10–11 victorias y decidí reservar una sola sesión de clases. Repasamos mi juego y corregimos algunos errores pequeños pero importantes en la defensa y en la toma de decisiones. Llegué a 13 victorias en mi siguiente Champs, mi mejor marca hasta ahora.',
      fr:
        'Je stagnais autour de 10–11 victoires et j’ai décidé de réserver une seule séance de coaching. On a passé mon jeu en revue et corrigé quelques erreurs petites mais importantes en défense et dans mes prises de décision. J’ai atteint 13 victoires lors de ma session de Champs suivante, mon meilleur score.',
    },
  },
]
