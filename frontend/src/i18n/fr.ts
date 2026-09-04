import type { Dictionary } from './en'

/**
 * French. Typed against the English dictionary, so a key added there and forgotten
 * here fails the build rather than the page.
 *
 * Register note: "tu" throughout, matching the Spanish. A FUT storefront addressing
 * players as "vous" reads like a utility bill.
 */
const fr: Dictionary = {
  nav: {
    trading: 'Crédits',
    boosting: 'Boosting',
    coaching: 'Coaching',
    rewards: 'Récompenses',
    track: 'Suivre ma commande',
    faqs: 'FAQ',
    tradersOnline: 'Traders en ligne',
    myAccount: 'Mon compte',
    console: 'Console',
    signIn: 'Se connecter',
    signOut: 'Se déconnecter',
    buyCoins: 'Acheter des crédits',
    openMenu: 'Ouvrir le menu',
    closeMenu: 'Fermer le menu',
    skipToContent: 'Aller au contenu',
    home: 'Accueil Global FUT Services',
    language: 'Langue',
    currency: 'Devise',
  },

  footer: {
    tagline:
      'Service de trading et de coaching pour EA FC. Nous travaillons le marché des ' +
      'transferts sur ton compte ; les crédits restent chez toi.',
    services: 'Services',
    menu: 'Menu',
    legal: 'Mentions légales',
    follow: 'Nous suivre',
    rewards: 'Récompenses',
    help: "Centre d'aide",
    support: 'Assistance',
    futClasses: 'Cours FUT',
    cards: 'Cartes joueur',
    terms: "Conditions d'utilisation",
    privacy: 'Politique de confidentialité',
    aml: 'LCB-FT et KYC',
    about: 'À propos',
    contact: 'Contact',
    refund: 'Politique de remboursement',
    cancellation: 'Politique d’annulation',
    shipping: 'Politique de livraison',
    rights:
      'Les crédits, objets et cartes joueur du jeu sont la propriété d’Electronic Arts ' +
      'Inc. Nous ne sommes pas affiliés à EA.',
    legalLanguageNotice:
      'Nos Conditions, notre Politique de confidentialité et notre politique LCB-FT et ' +
      'KYC sont publiées en anglais, et la version anglaise fait foi.',
  },

  common: {
    loading: 'Chargement…',
    tryAgain: 'Réessayer',
    cancel: 'Annuler',
    close: 'Fermer',
    comingSoon: 'Bientôt disponible',
  },

  loyalty: {
    otherCurrencyTitle: (loyalty) => `Les points sont réglés en ${loyalty}`,
    otherCurrencyBody: (shown, loyalty) =>
      `Les commandes en ${shown} ne rapportent pas de points, et un solde de points ne ` +
      `peut pas y être utilisé. Repasse en ${loyalty} pour cumuler et utiliser tes points.`,
  },

  proof: {
    eyebrow: 'Dans leurs mots',
    title: 'Ce que disent les clients',
    lead:
      'De vrais avis sur de vraies commandes, non retouchés. Filtre par service : ceux '
      + 'sur le coaching valent le détour avant de réserver.',
    all: 'Tout',
    trading: 'Crédits',
    boosting: 'Champs et Rivals',
    coaching: 'Coaching',
    countOf: (shown, total) => `${shown} sur ${total}`,
    range: (from: number, to: number, total: number): string => `${from}–${to} sur ${total}`,
    prev: 'Précédents',
    next: 'Suivants',
    pageOf: (page: number, pages: number): string => `Page ${page} sur ${pages}`,
    disclosure:
      'Chaque avis de cette page a été écrit par un client et est publié tel quel. Les '
      + 'résultats décrivent leur propre expérience : en coaching ils dépendent du '
      + 'joueur, et rien ici ne garantit un rang ni un nombre de victoires.',
    translated: 'Traduit de l’anglais',
    originalLabel: 'Les mots d’origine du client',
    showOriginal: 'Voir l’original',
    showTranslation: 'Voir la traduction',
  },

  home: {
    seoTitle: (season) => `Service de trading sécurisé pour EA ${season}`,
    seoDescription: (season) =>
      `Fais grandir ton club EA ${season} sans le grind. Nos traders travaillent le marché ` +
      `des transferts sur ton compte et les crédits restent chez toi. Livraison en moins ` +
      `d’une heure, 100% Safety Policy.`,

    promo: { label: 'Offres et annonces' },
    hero: {
      slideDiscount: 'Code de réduction GFS — 10 % de remise',
      slideBoosting: 'Boosting Champs, Rivals et Objectifs',
      slideSocial: 'Suivre Global FUT Services',
      liveNow: 'Traders en ligne',
      scroll: 'Faire défiler',
      ratesEyebrow: 'Tarifs en direct',
      titleLead: 'L’endroit le plus sûr, le plus rapide et le plus fiable pour acheter',
      titleAccent: () => 'crédits FC, boosting & coaching',
      subLine: 'Une solution pour chaque problème FC.',
      trustSpeed: 'La plupart en moins d’une heure',
      trustGuarantee: () => '100% Safety Policy',
      trustTax: 'Taxe EA déjà comprise',
      body:
        'Nos traders travaillent le marché des transferts pour toi : ils repèrent les cartes ' +
        'sous-cotées, les revendent et laissent le bénéfice dans ton club. Tu paies le ' +
        'travail, pas les crédits. La plupart des commandes arrivent en moins d’une heure.',
      startOrder: 'Passer commande',
      reviewsLink: 'Ce que les clients ont dit, dans leurs propres mots',
      reviewsLinkNote: 'Commandes réelles · non modifiées',
      seeBoosting: 'Boosting Champs et Rivals',
      statDeliveryValue: '< 60 min',
      statDeliveryLabel: 'Livraison habituelle',
      statShiftValue: '24 h/24',
      statShiftLabel: 'Traders de garde',
      statGuaranteeValue: '100% Safety Policy',
      statGuaranteeLabel: 'Chaque commande couverte',
      cardTitle: 'Service de trading sécurisé',
      cardSubtitle: (season) => `Tarifs en direct · ${season}`,
      popular: 'Populaire',
      perMillion: '/ million',
      ratesUpdating: 'Les tarifs sont en cours de mise à jour — reviens dans un instant.',
      orderFrom: (price) => `À partir de ${price} / million`,
      seePrices: 'Voir les tarifs',
      taxIncluded:
        'Les prix incluent la taxe du marché EA. Rien n’est ajouté à la dernière étape.',
    },

    trust: {
      delivery: 'La livraison la plus rapide',
      encrypted: 'Tes identifiants sont chiffrés puis supprimés',
      payments: 'Carte, UPI et virement',
      guarantee: 'Avec la 100% Safety Policy',
      humans: 'De vrais traders, pas des bots',
    },

    services: {
      eyebrow: (season) => `Ce que nous faisons en ${season}`,
      title: 'Emmène ton club tout en haut',
      lead:
        'Choisis celle qui te convient. Tout est tarifé à l’avance et tout est couvert par ' +
        'la même garantie.',
      tradingTag: 'Le plus commandé',
      tradingTitle: 'Service de trading sécurisé',
      tradingBody:
        'Nous travaillons le marché sur ton compte et le bénéfice reste dans ton club. ' +
        'Tarif au million, livré en moins d’une heure.',
      tradingCta: 'Passer commande',
      boostTag: 'Prêt pour le week-end',
      boostTitle: 'Champs et Rivals',
      boostBody:
        'Des victoires assurées par des joueurs qui le font chaque semaine. Choisis ton ' +
        'objectif, on s’occupe du reste avant la distribution des récompenses.',
      boostCta: 'Voir les paliers',
      coachTag: 'Réservations ouvertes',
      coachTitle: 'Cours FUT',
      coachBody:
        'Une heure en tête-à-tête avec un coach qui joue au niveau que tu vises. ' +
        'Une séance seule ou un bloc de six.',
      coachCta: 'Réserver une séance',
      pickTitle: 'Tu es là pour quoi ?',
      pickLead:
        'Trois choses différentes, trois pages différentes. Choisis celle qui correspond '
        + 'à ce que tu veux et on t’y emmène directement.',
    },

    why: {
      eyebrow: 'Pourquoi ils restent',
      title: 'Les six questions que tu allais poser',
      lead:
        'Des réponses directes, parce que tous ceux qui achètent des crédits ont les mêmes ' +
        'six inquiétudes et que presque aucun site n’y répond.',
      safeTitle: 'Sûr',
      safeBody: () =>
        'Chaque commande est couverte par notre 100% Safety Policy après la livraison. '
        + 'S’il arrive quelque chose à ton compte dans ce délai, on répare.',
      simpleTitle: 'Simple',
      simpleBody:
        'Choisis un montant, paie, et dis-nous où envoyer. La plupart des clients ont fini ' +
        'en deux minutes.',
      fastTitle: 'Rapide',
      fastBody:
        'Les commandes, quelle que soit leur taille, arrivent généralement en moins d’une ' +
        'heure. Notre délai affiché est plus long exprès : on préfère le battre que le ' +
        'discuter.',
      alwaysTitle: 'Toujours ouvert',
      alwaysBody:
        'Quelqu’un est de garde 24 h/24, y compris aux heures qui comptent pour la ' +
        'weekend league.',
      privateTitle: 'Confidentiel',
      privateBody:
        'Si une commande nécessite tes identifiants, ils sont chiffrés avant stockage et ' +
        'détruits dès que la commande est terminée. On te dit quand changer ton mot de passe.',
      rewardTitle: 'Récompensé',
      rewardBody: (points, spend, value) =>
        `Gagne ${points} points par tranche de ${spend} dépensée et grimpe six paliers qui ` +
        `retirent un pourcentage fixe de chaque commande. Chaque point vaut ${value} au paiement.`,
      rewardBodyFallback: 'Gagne des points sur chaque commande et dépense-les sur la suivante.',
      rewardStatEarn: 'points gagnés',
      rewardStatPer: (spend: string) => `par ${spend} dépensés`,
      rewardStatTiers: 'paliers de fidélité',
      rewardStatTiersNote: 'une remise permanente à chacun',
      rewardStatValue: 'par point',
      rewardStatValueNote: 'utilisables au paiement',
      rewardCta: 'Comment marchent les récompenses',
    },

    how: {
      eyebrow: 'Comment ça marche',
      title: 'Tu achètes le trading, pas les crédits',
      lead:
        'C’est important, et pas seulement juridiquement. Nous ne te vendons jamais de ' +
        'monnaie du jeu : ces actifs appartiennent à EA. Ce que tu paies, ce sont des gens ' +
        'qui connaissent le marché et le travaillent pour toi.',
      step1Title: 'Indique le montant',
      step1Body:
        'Choisis ta plateforme et le nombre de crédits à déplacer. Le prix affiché est le ' +
        'prix payé : la taxe du marché EA et les frais sont déjà inclus.',
      step2Title: 'On travaille le marché',
      step2Body:
        'Nos traders repèrent les cartes affichées sous leur valeur, les achètent et les ' +
        'revendent. C’est le service que tu paies.',
      step3Title: 'Le bénéfice reste chez toi',
      step3Body:
        'Les crédits atterrissent dans ton club. Tu reçois un e-mail dès que c’est fait, ' +
        'et la 100% Safety Policy à partir de là.',
    },

    rewards: {
      badge: 'Récompenses',
      title: 'Chaque commande finance la suivante',
      body: (earn, value, cap) =>
        `Gagne ${earn} dépensée. Chaque point vaut ${value} et se déduit automatiquement de ` +
        `ta prochaine commande, jusqu’à ${cap} du total. Ils te font aussi monter de six ` +
        `paliers, et une remise de palier n’expire jamais.`,
      howItWorks: 'Comment ça marche',
      createAccount: 'Créer un compte',
      pitchTitle: 'Plus tu achètes ici, moins tu paies',
      pitchOne: 'Des points de remise sur chaque commande',
      pitchOneBody: (value, cap) =>
        `Chaque point vaut ${value} au paiement, et jusqu’à ${cap} d’une commande peut être ` +
        `réglé avec eux. Aucun code à retenir : la remise est déjà appliquée.`,
      pitchTwo: 'Une remise de palier qui n’expire jamais',
      pitchTwoBody: (top) =>
        `Le total dépensé te fait monter et rien ne te fait redescendre. Dépenser tes points ` +
        `ne te coûte pas un palier. Le palier le plus haut est ${top}.`,
      pitchThree: 'Gratuit, et tu gagnes dès la première commande',
      pitchThreeBody: (daily) =>
        `Un compte suffit, et il gagne dès la première commande plutôt qu’après un montant ` +
        `minimum. Se connecter chaque jour ajoute ${daily} points.`,
      ladderTitle: 'Six paliers',
      ladderOff: (pct) => `${pct} de remise`,
      guestNote: 'Les commandes en invité ne peuvent ni gagner ni conserver de points.',
      exampleTitle: 'Exemple chiffré',
      youSpend: 'Tu dépenses',
      youEarn: 'Tu gagnes',
      worthAtCheckout: 'Valeur au paiement',
      pointsLand:
        'Les points arrivent une fois ta période de garantie close : une commande remboursée ' +
        'ne te laisse donc jamais courir après des points déjà repris.',
      pointsUnit: (points) => `${points} points`,
    },

    rail: {
      items: [
        'La livraison la plus rapide',
        'Traders en poste 24h/24',
        'Vos identifiants sont chiffrés puis détruits',
        'UPI · Cartes · Virement · Crypto',
        'Livré avec la 100% Safety Policy',
        'Taxe EA de 5 % couverte',
      ],
    },

    proof: {
      eyebrow: 'Niveau de service',
      title: 'Les chiffres qui nous engagent',
      lead:
        'Ce ne sont pas des chiffres marketing. Ce sont les engagements sur lesquels la ' +
        'garantie est rédigée, et c’est pour cela qu’ils sont prudents.',
      deliveryValue: '10–60+',
      deliveryUnit: 'min',
      deliveryLabel: 'Livraison habituelle',
      deliveryNote: 'Le délai annoncé est plus long, volontairement.',
      shiftValue: '24',
      shiftUnit: 'heures par jour',
      shiftLabel: 'En poste, tous les jours',
      shiftNote: 'Y compris pendant la weekend league.',
      guaranteeValue: '100%',
      guaranteeUnit: 'couvert',
      guaranteeLabel: '100% Safety Policy',
      guaranteeNote: 'Remboursement intégral ou un compte de remplacement — à vous de choisir.',
      tiersValue: '6',
      tiersUnit: 'paliers',
      tiersLabel: 'Paliers de fidélité',
      tiersNote: 'Une remise de palier n’expire jamais.',
    },

    coach: {
      eyebrow: 'En tête-à-tête',
      title: 'Progresse sur FC',
      body:
        'Une heure, en direct, avec un coach qui joue au niveau que vous visez. Il ' +
        'regarde comment vous jouez vraiment — pas comment vous croyez jouer — et corrige ' +
        'l’habitude qui vous coûte le plus de matchs.',
      point1: 'Vos propres séquences, mises en pause et décortiquées',
      point2: 'Des tactiques construites autour de votre défense',
      point3: 'Des choix d’effectif qui tiennent face au méta',
      point4: 'Une seule chose à travailler avant la prochaine séance',
      cta: 'Réserver une séance',
      secondary: 'Comment ça marche',
      durationLabel: 'Durée',
      durationValue: (m) => (m % 60 === 0 ? `${m / 60} heure${m === 60 ? '' : 's'}` : `${m} min`),
      durationNote: 'Par séance, en direct',
      formatLabel: 'Format',
      formatValue: 'Tête-à-tête',
      formatNote: 'Jamais en groupe',
      validityLabel: 'Validité',
      validityValue: '1 mois',
      validityNote: 'À partir du jour de l’achat',
    },

    ask: {
      eyebrow: 'Avant d’acheter',
      title: 'Posez n’importe quelle question. Réponse immédiate.',
      body:
        'Tarifs, sécurité, délais, ce qu’il advient de vos identifiants — tout est dans le chat ' +
        'en bas de l’écran, avec les règles qui font tourner le site. S’il ne peut pas vous ' +
        'aider, il vous passe quelqu’un.',
      cta: 'Ouvrir le chat',
    },

    closing: {
      eyebrow: 'Quand vous voulez',
      title: 'Ton club est à une commande d’ici',
      body:
        'Choisis un montant, paie comme tu veux, et retourne jouer. Si ça coince, on est de ' +
        'l’autre côté du chat.',
      startOrder: 'Passer commande',
      readFaqs: 'Lire la FAQ',
    },
  },

  comingSoon: {
    badge: 'Bientôt disponible',
    body:
      'Nous le construisons correctement plutôt que d’en livrer la moitié. Les crédits, le ' +
      'boosting et le coaching sont déjà disponibles et couverts par la même garantie — ' +
      'commence par là, et nous te préviendrons dès l’ouverture.',
    buyCoins: 'Acheter des crédits',
    seeBoosting: 'Voir le boosting',
    seoDescription: (service, season) => `${service} pour EA ${season} — disponible très bientôt.`,
  },

  notFound: {
    title: 'Introuvable',
    heading: 'Cette page n’existe pas',
    body: 'Si tu viens d’un e-mail de commande, essaie plutôt de suivre ta commande.',
    home: 'Accueil',
    track: 'Suivre une commande',
  },

  boosting: {
    seoTitle: (season) => `Boosting Champs et Rivals ${season}`,
    seoDescription: (season) =>
      `Montées en victoires Champs et en divisions Rivals pour EA ${season}, assurées par ` +
      `des joueurs qui le font chaque semaine.`,
    eyebrow: (season) => `${season} · Boosting`,
    title: 'Laisse la manette à quelqu’un qui joue à ce niveau',
    lead:
      'Victoires Champs et montées Rivals, réglées avant la distribution des récompenses. ' +
      'Même garantie, même chiffrement, mêmes personnes.',
    tabChamps: 'Victoires Champs',
    tabRivals: 'Divisions Rivals',
    bestValue: 'Meilleur rapport',
    wins: 'victoires',
    successRateLabel: (pct) => `${pct} de réussite`,
    successRateNote: 'Mesuré sur les commandes éligibles terminées.',
    successHeadline: (range, pct) =>
    `${range} atteint dans ${pct} des commandes éligibles.`,
    choose: 'Choisir',
    tiersUpdating: 'Les paliers de ce service sont en cours de mise à jour.',
    knowEyebrow: 'Ce qu’il faut savoir',
    knowTitle: 'La version honnête',
    signInTitle: 'Il faut tes identifiants',
    signInBody:
      'Quelqu’un doit jouer les matchs, donc le boosting est toujours un comfort trade. Tes ' +
      'informations sont chiffrées avant stockage, ouvertes uniquement par le joueur qui ' +
      'traite ta commande, et détruites une fois terminé.',
    timingTitle: 'Le timing compte',
    timingBody:
      'Champs et Rivals suivent l’horloge hebdomadaire d’EA. Commande tôt dans la fenêtre et ' +
      'il y a de la marge ; commande le dernier soir et il n’y en aura peut-être pas.',
    discordTitle: 'Parlez-nous sur Discord',
    discordBody:
      'Si une série n’atteint pas le rang commandé, la différence de prix vous est ' +
        'créditée — utilisable sur une autre commande ou remboursable. Les réclamations ' +
        'passent par notre Discord officiel.',
    coveredTitle: 'Tu es couvert',
    coveredBody: (cash, credit) =>
      `Notre 100% Safety Policy s’applique. Si EA sanctionne le compte dans ce ` +
      `délai, tu récupères ${cash}% en espèces ou ${credit}% en avoir — à toi de choisir.`,
  },


  track: {
    seoTitle: 'Suivre ta commande',
    eyebrow: 'Statut de la commande',
    title: 'Où en est ma commande ?',
    lead:
      'Ta référence et l’e-mail utilisé. Les deux, parce qu’une référence seule circule dans ' +
      'les captures d’écran et les conversations d’assistance.',
    reference: 'Référence de commande',
    email: 'E-mail',
    find: 'Trouver ma commande',
    emptyHint:
      'Ta référence figure dans l’e-mail de confirmation — elle ressemble à GFS-26 suivi de ' +
      'huit caractères.',
    total: 'Total',
    placed: 'Passée le',
    deliveryMethod: 'Mode de livraison',
    breakdown: 'Détail',
    history: 'Historique',
    payTitle: 'En attente de paiement',
    payBody:
      'Cette commande n’a pas encore été payée. Si tu as fermé la fenêtre de paiement, ' +
      'recommence depuis la page de commande : ta référence sera réutilisée.',
    // -- livraison en cours -------------------------------------------------
    progressTitle: 'Coins livrés',
    progressOf: (done: string, total: string) => `${done} sur ${total}`,
    action: {
      RESUBMIT_SIGN_IN: "Tes identifiants EA ont été refusés. Renvoie-les ci-dessous et nous reprenons.",
      NEW_BACKUP_CODES: "Ces codes de secours sont déjà utilisés ou incorrects. Génère-en de nouveaux puis envoie-les ci-dessous.",
      SIGN_OUT_CONSOLE: "Déconnecte-toi d'EA sur la console, la web app et la companion app. Nous réessaierons seuls.",
      CLEAR_UNASSIGNED_ITEMS: "Tu as trop d'objets non attribués. Descends sous 50 et nous réessaierons.",
      FREE_TRANSFER_SLOTS: "Ta liste de transferts est pleine. Libère au moins trois places dans la liste et les cibles.",
      ADD_COINS: "Ton club doit avoir plus de 1 500 coins avant que nous puissions commencer.",
      SOLVE_CAPTCHA: "EA demande un captcha. Connecte-toi une fois sur la web app, résous-le, puis déconnecte-toi.",
      FIX_PERSONA: "La mauvaise persona EA est sélectionnée. Passe sur celle qui a ton club.",
      ACCOUNT_UNUSABLE: "Ce compte est inutilisable : pas d'accès au marché ou pas de club. Écris-nous, nous te remboursons.",
      BANNED: "EA a restreint ce compte. Écris-nous : c'est à cela que sert la garantie.",
      SUPPLIER_SIDE: "Celui-ci vient de nous, pas de toi. Nous nous en occupons et t'informons ici.",
    },
    credentialsTitle: 'Il nous faut tes identifiants pour commencer',
    credentialsBody:
      'Connecte-toi à ton compte pour les transmettre en sécurité. Ils sont chiffrés avant ' +
      'stockage et supprimés une fois la commande terminée.',
    // -- formulaire d'identifiants -----------------------------------------
    credFormTitle: 'Envoie tes identifiants EA',
    credFormLead:
      "Chiffrés avec une clé propre à cette commande avant d'être enregistrés, ouverts " +
      "uniquement par le trader qui la traite, et détruits dès qu'elle est terminée.",
    credFormRetention: (days: string) =>
      `Supprimés automatiquement au bout de ${days}, même si quelque chose échoue chez nous.`,
    howToFind: 'Où le trouver ?',
    credEmail: 'E-mail du compte EA',
    credEmailHint: "L'adresse avec laquelle tu te connectes à EA, pas celle de livraison.",
    credPassword: 'Mot de passe EA',
    credPasswordHint: 'Change-le dès la livraison — nous te le rappellerons.',
    credShow: 'Afficher le mot de passe',
    credHide: 'Masquer le mot de passe',
    credBackupCodeN: (n) => `Code de secours ${n}`,
    credReassureLead: 'Tes identifiants sont chiffrés, ouverts uniquement lorsque c’est nécessaire pour traiter ta commande, puis supprimés aussitôt — voir nos',
    credReassureLink: 'Conditions d’utilisation',
    credReassureTail: 'pour le détail.',
    credBackupCodes: 'Codes de secours',
    credBackupCodesFind: 'Où trouver les codes de secours',
    credBackupCodesHint: 'Un par ligne. Utiles si ton compte demande un code à la connexion.',
    credHandle: 'Gamertag ou identifiant PSN',
    credHandleHint: "Facultatif — aide le trader à confirmer qu'il est sur le bon compte.",
    credNote: 'Autre chose à nous signaler ?',
    credAckSignedOut:
      'Je suis déconnecté sur console, sur la web app et sur la companion app.',
    credAckMarket: 'Mon marché des transferts est déverrouillé.',
    credAckItems: "J'ai moins de cinq objets non attribués.",
    credAckTerms: 'Je comprends que ces données sont chiffrées puis supprimées à la fin.',
    credSubmit: 'Envoyer en sécurité',
    credSubmitting: 'Chiffrement…',
    credDone: 'Nous avons tes identifiants',
    credDoneBody:
      'Rien de plus à faire. Ils sont chiffrés et seront supprimés dès que cette commande ' +
      'sera marquée terminée.',
    credSignInFirst: 'Connecte-toi pour les envoyer',
    credError: 'Envoi impossible. Vérifie les champs et réessaie.',
    stuckTitle: 'Nous sommes bloqués',
    stuckBody:
      'En général cela signifie que le compte était connecté, que le marché des transferts ' +
      'était verrouillé, ou qu’il y avait trop d’objets non attribués. Écris-nous et on règle ça.',
    deliveredTitle: 'Livrée — deux choses à faire',
    deliveredBody: (until) =>
      `Change ton mot de passe EA et régénère tes codes de secours. Tout ce que tu nous as ` +
      `confié a déjà été détruit. Ta garantie court jusqu’au ${until}.`,
    reviewTitle: 'En cours d’examen',
    reviewBody: 'Nous étudions ta demande de garantie et nous reviendrons vers toi.',
  },

  auth: {
      continueGoogle: 'Continuer avec Google',
      continueDiscord: 'Continuer avec Discord',
      orDivider: 'ou',
      oauthNoEmail: 'Ce compte n’a pas communiqué d’adresse e-mail, la connexion a donc échoué. Utilise ton e-mail et ton mot de passe.',
      oauthUnverified: 'Un compte existe déjà pour cet e-mail. Connecte-toi une fois avec ton mot de passe, puis tu pourras les lier.',
      oauthFailed: 'Cette connexion n’a pas abouti. Réessaie ou utilise ton e-mail et ton mot de passe.',
    signInTitle: 'Se connecter',
    registerTitle: 'Créer un compte',
    signInHeading: 'Bon retour',
    registerHeading: 'Crée ton compte',
    signInLead: 'Connecte-toi pour voir tes commandes et tes points.',
    registerLead: 'Gagne des points sur chaque commande et garde ton historique au même endroit.',
    email: 'E-mail',
    password: 'Mot de passe',
    passwordHint:
      'Au moins 12 caractères. Une courte phrase fonctionne bien et se retient mieux qu’un ' +
      'mot tordu.',
    displayName: 'Nom affiché',
    acceptPrefix: 'J’accepte les',
    acceptTerms: 'conditions d’utilisation',
    acceptAnd: 'et la',
    acceptPrivacy: 'politique de confidentialité',
    createAccount: 'Créer un compte',
    signInButton: 'Se connecter',
    haveAccount: 'Tu as déjà un compte ?',
    newHere: 'Nouveau ici ?',
    signInLink: 'Se connecter',
    createLink: 'En créer un',
    guestNote:
      'Pas besoin de compte pour commander — le paiement invité fonctionne très bien. Le ' +
      'compte sert à cumuler des points.',
    genericError: 'Une erreur est survenue. Réessaie.',
  },

  support: {
    seoTitle: 'Assistance',
    seoDescription: 'Contacte-nous au sujet d’une commande.',
    eyebrow: 'Assistance',
    title: 'Dis-nous ce qui se passe',
    lead:
      'Quelqu’un est de garde 24 h/24. Indique ta référence de commande si tu en as une et ' +
      'nous la retrouvons immédiatement.',
    yourEmail: 'Ton e-mail',
    orderRef: 'Référence de commande',
    orderRefHint: 'Facultatif, mais cela accélère les choses.',
    subject: 'Objet',
    message: 'Que se passe-t-il ?',
    noPassword: 'Je n’ai pas inclus mon mot de passe ni mes codes de secours dans ce message.',
    noPasswordNote:
      'Nous ne te les demanderons jamais ici. Si une commande nécessite tes identifiants, ils ' +
      'sont recueillis via le formulaire chiffré de la commande elle-même.',
    send: 'Envoyer le message',
    sendFailed: 'Envoi impossible.',
    fasterTitle: 'Réponses plus rapides',
    faster1: 'Commande à l’arrêt ? Vérifie d’abord que tu es déconnecté partout.',
    faster2: 'Montant incorrect ? Envoie-nous la référence et une capture d’écran.',
    faster3: 'Bannissement ou retrait de crédits ? Indique la date à laquelle c’est arrivé.',
    neverTitle: 'Ce que nous ne ferons jamais',
    neverBody:
      'Te demander ton mot de passe par chat ou e-mail, te demander de payer en dehors du ' +
      'site, ou te contacter en premier au sujet d’un « problème » sur ta commande. Si ' +
      'quelqu’un le fait, ce n’est pas nous.',
  },

  account: {
    seoTitle: 'Mon compte',
    greeting: (name) => `Bonjour, ${name}`,
    fallbackTitle: 'Ton compte',
    yourOrders: 'Tes commandes',
    noOrders: 'Aucune commande pour l’instant',
    noOrdersBody: 'Dès que tu en passes une, elle apparaît ici avec tout son historique.',
    statement: 'Relevé de points',
    rewardPoints: 'Points de récompense',
    worth: 'valent',
    atCheckout: 'au paiement',
    earnedTotal: (total, cap) =>
      `${total} gagnés au total. Les points peuvent couvrir jusqu’à ${cap}% d’une commande ` +
      `et arrivent à la clôture de chaque période de garantie.`,
    coaching: 'Coaching',
    sessionsLeftOne: 'séance à réserver',
    sessionsLeftMany: 'séances à réserver',
    useThemBy: (date) => `À utiliser avant le ${date}.`,
    bookSession: 'Réserver une séance',
    manageSessions: 'Gérer mes séances',
    quickActions: 'Actions rapides',
    startOrder: 'Passer commande',
    needChange: 'Besoin de modifier une commande en cours ?',
    contactSupport: 'Contacte l’assistance',
    withReference: 'avec la référence.',
  },

  order: {
    seoTitle: (season) => `Acheter des crédits ${season}`,
    seoDescription:
      'Choisis ta plateforme et ton montant. Tarifs en direct, aucun frais caché au paiement.',
    pricesUnavailable: 'Les tarifs sont indisponibles',
    title: 'Compose ta commande',
    lead: 'Tout est tarifé à l’avance. Rien n’est ajouté à la dernière étape.',
    stepPackage: (_n, service) => service,
    stepPlatform: () => 'Plateforme',
    stepAmount: () => 'Montant',
    stepDiscounts: () => 'Remises',
    perMillion: '/ million',
    taxIncludedShort: 'Inclus',
    taxIncludedInline: 'Taxe EA de 5 % incluse',
    taxIncludedTitle: 'Les 5 % d’EA sont pour nous',
    taxIncludedBody:
      'EA prélève 5 % sur chaque transfert. La plupart te les ajoutent au ' +
      'paiement. Pas nous : le prix au million que tu vois est celui que tu paies.',
    amountAria: 'Montant de crédits en millions',
    savingsTitle: 'Remises et récompenses',
    savingsEmpty: 'Aucune remise pour l’instant — un code ou des points s’afficheraient ici.',
    youSave: 'Tu économises',
    amountManualLabel: 'Ou saisis un montant exact',
    amountManualHint: (stepK, minK, maxK) =>
      `En milliers, par tranches de ${stepK}K. Entre ${minK} et ${maxK}.`,
    amountManualUnit: 'K',
    amountSnapped: (shown) => `Arrondi au palier le plus proche — ${shown}.`,
    requirementsTitle: 'Conditions pour commander',
    requirementsLead:
      'Quatre choses doivent être vraies avant que nous puissions transférer des crédits. ' +
      'Autant vérifier maintenant : chacune est une commande qui bloque après paiement.',
    reqCompanion: 'Accès à la Web ou Companion App',
    reqCompanionNote: 'C’est par là que passe l’échange. Sans accès, pas de transfert.',
    reqMarket: 'Marché des transferts débloqué',
    reqMarketNote: 'Un compte neuf ou récemment réinitialisé est souvent encore bloqué.',
    reqMinCoins: 'Au moins 5 000 crédits dans le club',
    reqMinCoinsNote: 'Mettre une carte en vente demande un solde de départ.',
    reqUnassigned: 'Moins de 5 objets non assignés',
    reqUnassignedNote: 'Une pile pleine bloque totalement les transferts.',
    deliveryFixed: 'GFS Trading Method 3.0 (Latest)',
    deliveryFixedHint:
      'Notre méthode actuelle, utilisée pour chaque commande de crédits. Rien à choisir.',

    /* « Md » pour milliard, et la virgule decimale francaise. */
    volumeLabel: 'Crédits transférés',
    volumeValue: '5,5 Md+',
    volumeNote: (season: string) => `Sur les commandes clients en ${season}.`,
    tabDiscount: 'Remise',
    tabRewards: 'Points',
    rewardsNoAccount: 'Connecte-toi pour utiliser des points sur cette commande.',
    rewardsNoneYet: 'Tu n’as pas encore de points à dépenser.',
    rewardsCapNote: (cap) => `Jusqu’à ${cap} d’une commande peut être payé en points.`,
    rewardsUseAll: 'Tout utiliser',
    rewardsClear: 'Retirer',
    and: 'et',
    consentLead: 'Je comprends et j’accepte les',
    consentTerms: 'Conditions d’utilisation',
    consentPrivacy: 'Politique de confidentialité',
    consentAml: 'Politique AML et KYC',
    howToFind: 'Où le trouver ?',
    couponLabel: 'Code promo',
    couponApply: 'Appliquer',
    couponHint: 'Tu as un code de réduction ? Saisis-le ici.',
    couponApplied: (code) => `${code} appliqué.`,
    pointsLabel: 'Points de récompense',
    pointsHintUsable: (balance, usable) =>
      `Tu as ${balance}. Jusqu’à ${usable} utilisables ici.`,
    pointsHintPlain: (balance) => `Tu as ${balance} points.`,
    createAccount: 'Crée un compte',
    createAccountRest: 'pour gagner des points sur cette commande et les dépenser sur la suivante.',

    summaryTitle: 'Ta commande',
    total: 'Total',
    earnsPoints: (points) =>
      `Tu gagneras environ ${points} points, une fois ta fenêtre de garantie fermée.`,
    earnsPointsGuest: (points) =>
      `Connecte-toi pour gagner ${points} points sur cette commande — les commandes en invité n’en gagnent pas.`,
    continue: 'Continuer',
    priceNote:
      'Les prix sont bloqués quelques minutes et revérifiés au paiement : ce que tu vois ici ' +
      'est ce qui sera débité.',
    refreshingPrice: 'Actualisation du prix…',
    priceHeld: (time) => `Prix bloqué ${time}`,

    cartTitle: 'Ton panier',
    cartRemove: 'Retirer du panier',
    continueShopping: 'Continuer les achats',
    userInfoTitle: 'Tes informations',
    fullName: 'Nom complet',
    fullNameHint: 'Le nom qui figurera sur ton reçu.',
    countryCode: 'Indicatif pays',
    phoneHelper: 'Utilisé uniquement en cas de problème urgent — jamais de spam.',
    discordHelper: 'Nous pouvons te contacter sur Discord pour un suivi plus rapide.',
    discordLabel: 'Discord (facultatif)',
    discordPlaceholder: 'tonpseudo',
    whereTitle: 'Où faut-il livrer ?',
    email: 'E-mail',
    emailHint: 'Ton reçu et l’avis de livraison arrivent ici.',
    phone: 'Téléphone',
    phoneHint: 'Facultatif — seulement si nous bloquons.',
    eaName: 'Nom de compte EA',
    eaNameHint: 'Pour que le trader te retrouve.',
    eaNamePlaceholder: 'Gamertag ou identifiant EA',
    deliveryMethod: 'Mode de livraison',
    deliveryAuctionHint:
      'Tu mets une carte en vente et nous l’achetons sur le marché. Nous demandons tes '
      + 'identifiants après le paiement ; ils sont chiffrés puis supprimés à la fin.',
    deliveryComfortHint:
      'Nous nous connectons et tradons directement. Plus rapide pour les gros montants ; ' +
      'nous te demanderons tes identifiants après le paiement.',
    deliveryAuction: 'Marché des transferts — tu listes, nous achetons',
    deliveryComfort: 'Comfort trade — nous nous connectons pour toi',
    coachingNextTitle: 'La suite',
    coachingNextBody:
      'Tes séances arrivent dans ton compte dès que le paiement est confirmé, et tu les ' +
      'réserves depuis la page coaching quand tu veux. Le coaching ne nécessite jamais tes ' +
      'identifiants EA : tu joues sur ton propre compte pendant que ton coach observe.',
    signInTitle: 'À propos de tes identifiants',
    signInBody:
      'Nous te les demanderons après le paiement, jamais avant. Ils sont chiffrés dès ' +
      'réception, ouverts uniquement par le trader qui traite ta commande, et détruits une ' +
      'fois terminée. Nous te rappellerons de changer ton mot de passe ensuite.',
    noteLabel: 'Quelque chose à signaler ?',
    notePlaceholder: 'Facultatif',
    beforeYouPay: 'Avant de payer',
    readyCheck:
      'Mon marché des transferts est déverrouillé, j’ai au moins 5 000 crédits et moins de ' +
      'cinq objets non attribués, et je suis déconnecté sur console, web app et companion app.',
    termsPrefix: 'J’ai lu les',
    termsLink: 'conditions d’utilisation',
    termsCoaching: 'et je comprends que j’achète un service de coaching.',
    termsTrading:
      'et je comprends que j’achète un service de trading, pas de la monnaie du jeu.',
    back: 'Retour',
    pay: (amount) => `Payer ${amount}`,
    acceptTermsError: 'Accepte les conditions pour passer ta commande.',
    readyChecksError: 'Confirme que ton compte est prêt — cela nous évite un retard à tous les deux.',

    orderCreated: 'Commande créée',
    keepReference:
      'Garde cette référence — c’est ainsi que tu suis ta commande et que l’assistance la retrouve.',
    stubTitle: 'La passerelle de paiement n’est pas configurée',
    stubBody:
      'Cet environnement fonctionne sans identifiants de paiement réels, donc aucun argent ne ' +
      'circulera. La commande existe et apparaît dans la console d’exploitation. Configure les ' +
      'clés Razorpay pour activer les paiements réels.',
    payWindowFailed: 'La fenêtre de paiement ne s’est pas ouverte. Vérifie ta connexion et réessaie.',
    trackOrder: 'Suivre cette commande',
    coachingNextStub:
      'Les séances sont créditées quand un paiement est confirmé. Aucun paiement ne peut '
      + 'aboutir dans cet environnement, donc rien n’a été crédité et le calendrier '
      + 'restera masqué. Sur le site réel, c’est ici que tu irais réserver.',
    coachingNextCta: 'Aller à la réservation',
    coachingNextCtaNow: 'Réserve ta première séance',
  },

  help: {
    seoTitle: 'Centre d’aide',
    seoDescription: 'Les réponses aux questions qu’on nous pose vraiment avant de commander.',
    eyebrow: 'Centre d’aide',
    title: 'Les questions qu’on nous pose vraiment',
    lead:
      'Si la réponse n’est pas ici, l’assistance est à un message et quelqu’un est de garde.',

    groupBefore: 'Avant de commander',
    groupOrdering: 'Commande et livraison',
    groupMoney: 'Paiement et assistance',

    qServices: 'Quels services propose Global FUT Services ?',
    aServices:
      'Nous proposons trois services principaux pour améliorer ton expérience FUT : des ' +
      'transferts de coins FUT sûrs et rapides, du boosting FUT Champs pour atteindre le ' +
      'rang que tu vises, et des séances de coaching FUT personnalisées pour faire ' +
      'progresser ton jeu.',

    qSafety: 'Comment puis-je être sûr que mon compte est en sécurité ?',
    aSafety:
      'La sécurité de ton compte est notre priorité absolue. Avec plus de quatre ans dans ' +
      'le secteur et un historique de deux milliards de coins transférés et 1 400 boosts ' +
      'réussis, nous utilisons des méthodes sûres et éprouvées qui protègent ton compte à ' +
      'chaque étape.',

    qPartners: 'Êtes-vous partenaires de sources de confiance ?',
    aPartners:
      'Oui. Nous sommes partenaires des chaînes YouTube Vibhor Sharma et FC Breakdown ' +
      'depuis près de trois ans. Ces partenariats reflètent notre exigence d’intégrité et ' +
      'un service auquel tu peux te fier.',

    qSpeed: 'En combien de temps aurai-je des résultats ?',
    aSpeed:
      'Les transferts de coins sont livrés en 10 à 30 minutes. Le boosting Champs se fait ' +
      'avec des délais minimes. Les séances de coaching sont planifiées à l’heure qui ' +
      't’arrange.',

    qOrdering: 'Comment passer une commande ?',
    aOrdering:
      'Configure ta commande sur le site, choisis ta plateforme et ton montant, puis paie. ' +
      'Tu reçois une référence de commande immédiatement et notre équipe prend le relais. Si ' +
      'tu préfères en parler d’abord, contacte l’assistance et quelqu’un t’aidera.',

    qCredentials: 'De quoi avez-vous besoin pour acheter ou vendre des coins ?',
    aCredentials:
      'Pour toute commande de coins — avec les deux modes de livraison — nous avons ' +
      'besoin de ton e-mail et de ton mot de passe EA ' +
      'ainsi que de trois codes de secours EA, et nous ne te les demandons qu’après ton ' +
      'paiement. Vérifie que ton compte a accès au marché des transferts sur la EA Web App ' +
      'pour que tout se passe bien. Une fois la commande lancée tu recevras une ' +
      'confirmation, et il vaut mieux ne pas te connecter avant la fin pour ne rien ' +
      'interrompre.',

    qTax: 'Et la taxe EA de 5 % sur mon achat de coins ?',
    aTax:
      'EA prélève 5 % sur chaque transfert du marché — c’est une taxe d’EA, pas la ' +
      'nôtre. Nous l’affichons sur une ligne à part dans ton devis avant le paiement, ' +
      'plutôt que de la noyer dans le prix affiché : le montant que tu vois est celui ' +
      'qui est prélevé. Rien n’est ajouté ensuite.',

    qPayment: 'Quels moyens de paiement acceptez-vous ?',
    aPayment:
      'UPI — dont GPay, PhonePe, Paytm et CRED — ainsi que les cartes de débit et ' +
      'de crédit et le virement en ligne, le tout via Razorpay. PayPal est possible sur ' +
      'demande avec des frais supplémentaires. Skrill et Bitcoin ne sont pas encore actifs.',

    qBackupCodes: 'Où trouver mes codes de secours ?',
    aBackupCodes:
      'Les codes de secours viennent de ton compte EA, pas de nous. Connecte-toi aux '
      + 'paramètres de ton compte EA, ouvre la section Sécurité et cherche la '
      + 'vérification de connexion : les codes de secours sont là, et tu peux en générer '
      + 'de nouveaux à tout moment. Copie toute la liste et colle-la ; nous en utilisons '
      + 'un et les autres restent à toi. Génère-en de nouveaux une fois ta commande '
      + 'terminée, les anciens cesseront de fonctionner. Si la section n’est pas là où '
      + 'c’est décrit, EA l’a déplacée : écris-nous sur Discord et on te guidera.',
    qRefund: 'Quelle est votre politique de remboursement ?',
    aRefund:
      'Une fois le travail commencé, une commande ne peut pas simplement être annulée : ' +
      'les coins circulent déjà. Tu n’es pas sans protection pour autant — chaque ' +
      'commande a une garantie de 7 jours à partir de la livraison, et si la réclamation ' +
      'aboutit tu choisis 100 % en avoir ou 50 % en espèces. Si nous ne pouvons pas ' +
      'honorer une commande du tout, tu es remboursé.',

    qSupport: 'Et si j’ai des questions pendant le processus ?',
    aSupport:
      'Nous sommes là en permanence. Notre équipe d’assistance 24h/24 et 7j/7 est toujours ' +
      'prête à répondre à une question ou à régler un souci — écris-nous quand tu veux.',

    stillStuck: 'Toujours bloqué ?',
    stillStuckBody: 'Dis-nous ce qui se passe et on s’en occupe.',
    contactSupport: 'Contacter l’assistance',
  },

  rewards: {
    seoTitle: 'Récompenses',
    seoDescription:
      'Gagne des points sur chaque commande, grimpe six paliers, et obtiens une remise ' +
      'automatique au paiement qui n’expire jamais.',
    loading: 'Chargement du programme de récompenses…',
    eyebrow: 'Récompenses',
    title: 'Deux façons d’être remboursé',
    lead:
      'Chaque commande rapporte des points à dépenser sur la suivante. Ces mêmes points te ' +
      'font monter de six paliers, et chaque palier retire un pourcentage fixe de tout ce que ' +
      'tu commandes ensuite.',
    earningLabel: 'Tu gagnes',
    earningUnit: (points) => `${points} pts`,
    earningPer: (spend) => `par tranche de ${spend} dépensée`,
    spendingLabel: 'Tu dépenses',
    spendingPer: 'par point au paiement',
    topLabel: 'Au sommet',
    effectiveLabel: 'Effectif',
    topBody: (tier) => `en points, plus une remise ${tier} sur chaque commande`,
    effectiveBody: 'sur tout ce que tu commandes',
    tiersTitle: 'Les six paliers',
    tiersLead:
      'Les paliers se gagnent sur les points cumulés à vie — le total de tout ce que tu as ' +
      'jamais gagné. Dépenser ton solde ne te fait jamais redescendre, et ta remise de palier ' +
      's’applique automatiquement au paiement, sans rien à saisir.',
    colTier: 'Palier',
    colPoints: 'Points cumulés',
    colDiscount: 'Remise',
    fromFirstOrder: 'Dès ta première commande',
    rulesTitle: 'Les règles, en entier',
    rulePointsLand: 'Les points arrivent à la clôture de ta garantie.',
    rulePointsLandBody: (days) =>
      `${days} jours après la livraison, pas au paiement. Ainsi une commande remboursée ne te ` +
      `laisse jamais courir après des points déjà repris.`,
    ruleCap: (cap) => `Jusqu’à ${cap} d’une commande peut être payé en points.`,
    ruleCapBody:
      'Un plafond simplifie les remboursements et empêche le système de devenir quelque chose ' +
      'à farmer.',
    ruleNoDemote: 'Dépenser des points ne te fait jamais redescendre.',
    ruleNoDemoteBody:
      'Ton palier suit tes gains cumulés : utiliser la récompense que nous t’avons donnée ne ' +
      'te coûte pas le statut que tu as gagné en l’obtenant.',
    ruleDaily: (points) => `${points} points par jour, juste pour passer.`,
    ruleDailyBody:
      'Une fois par jour, depuis ton compte. Ils se dépensent comme n’importe quel point et ' +
      'comptent pour ton palier.',
    ruleNoCash: 'Les points n’ont aucune valeur en espèces.',
    ruleNoCashBody:
        'Ils constituent une remise sur vos prochaines commandes, pas un solde retirable. Les '
        + 'commandes en tant qu’invité ne peuvent ni en gagner ni en conserver.',
    startedTitle: 'Pour commencer',
    startedBody:
      'Les points nécessitent un compte — une commande invité n’a nulle part où les mettre. ' +
      'En créer un prend un instant et ton solde est visible dès la première commande, avec un ' +
      'relevé qui montre exactement d’où vient chaque point.',
    createAccount: 'Créer un compte',
    startOrder: 'Passer commande',
    standingEyebrow: 'Où tu en es',
    discountOffEvery: (pct) => `${pct} de remise sur chaque commande`,
    lifetimeLine: (lifetime, balance) =>
      `${lifetime} points cumulés · ${balance} disponibles à dépenser`,
    toNextTier: (points, tier) => `${points} points de plus pour ${tier}.`,
    claim: (points) => `Réclamer +${points}`,
    claimedToday: (points) => `Tes ${points} points du jour sont réclamés.`,
    comeBack: 'Reviens demain.',
    claimFailed: 'Impossible de réclamer le bonus du jour.',
  },

  coaching: {
    seoTitle: (season) => `Coaching FUT ${season}`,
    seoDescription: (season) =>
      `Coaching individuel EA ${season} : construction d’équipe, trading et les habitudes qui ` +
      `font vraiment monter ton rang. Réserve une séance seule ou un bloc de six.`,
    eyebrow: (season) => `${season} · Coaching`,
    title: 'Progresse volontairement, pas par hasard',
    lead:
      'Une heure, en tête-à-tête, sur ton équipe et tes habitudes. On te regarde jouer, ' +
      'on s’arrête là où ça compte, et on te laisse quelque chose que tu peux reproduire.',
    creditsLeft: (n) => (n === 1 ? 'séance' : 'séances'),
    creditsAlertPrefix: 'Il te reste',
    creditsAlertSuffix: '. Choisis un coach et un horaire ci-dessous.',
    loadCoachesFailed: 'Impossible de charger les coachs.',
    needMore: 'Besoin de plus de séances ?',
    pricingHeading: 'Tarifs du coaching',
    packBody:
      'Six séances à utiliser en un mois. Réserve-les au fur et à mesure, une par une, au ' +
      'rythme qui te convient.',
    singleBody:
      'Une séance. Un bon moyen de voir si cela te convient avant de t’engager sur un bloc.',
    buyBlock: 'Acheter le pack',
    buySession: 'Acheter une séance',
    saveBadge: (pct) => `Économise ${pct}%`,
    whoTitle: 'Rencontre ton coach',
    peak: 'Meilleur niveau :',
    speaks: 'Parle :',
    bookTitle: 'Réserver une séance',
    noCoachesTitle: 'Aucun coach ne prend de réservations pour l’instant',
    noCoachesBody:
      'Tes séances n’expirent pas encore — nous t’écrirons dès qu’une place se libère.',
    previousMonth: 'Mois précédent',
    nextMonth: 'Mois suivant',
    dayAvailable: 'créneaux disponibles',
    dayUnavailable: 'aucun créneau',
    noSlotsInMonth: (month) => `Aucun créneau en ${month}`,
    timesShownIn: 'Horaires affichés en',
    coachesFrom: (name, zone) => `${name} coache depuis ${zone}`,
    coachDiscord:
      'Après la commande, contactez votre coach ou le support GFS sur notre Discord officiel ' +
      'pour la planification et les échanges liés à la séance :',
    policyLine: (minutes, hours) =>
      `${minutes} minutes · déplaçable gratuitement jusqu’à ${hours} h avant`,
    policyLineBoth: (single, block, hours) =>
      `${single} minutes à l’unité, ${block} minutes en pack · déplaçable jusqu’à ${hours}h avant`,
    bookedFor: 'Réservée le',
    emailedDetails: '. Nous t’avons envoyé les détails par e-mail.',
    loadSlotsFailed: 'Impossible de charger les disponibilités.',
    bookingFailed: 'La réservation n’a pas abouti.',
    noSlotsTitle: 'Aucun créneau libre dans les deux prochaines semaines',
    noSlotsBody: (name) =>
      `${name} est complet. Essaie un autre coach, ou reviens plus tard — les annulations ` +
      `libèrent régulièrement des créneaux.`,
    upcomingTitle: 'Tes prochaines séances',
    alsoAppears: 'Chaque séance apparaît aussi dans',
    yourAccount: 'ton compte',
    withCoach: 'avec',
    join: 'Rejoindre',
    cancel: 'Annuler',
    cancelRefunds: 'Annuler cette séance ? Le crédit retourne sur ton solde.',
    cancelForfeits:
      'Annuler cette séance ? Tu es dans le délai de préavis, la séance est donc perdue.',
    cancelFailed: 'Impossible d’annuler cette séance.',
  },

  chat: {
    launcher: 'Poser une question',
    close: 'Fermer',
    assistant: 'Assistant FUT',
    status: 'Réponse immédiate',
    greeting:
      'Bonjour. Je peux répondre tout de suite aux questions qu’on nous pose le plus. ' +
      'Choisis-en une ci-dessous, ou parle à quelqu’un si tu préfères.',
    suggestions: 'Questions fréquentes',
    more: 'Plus de questions',
    typing: 'Écrit',
    anythingElse: 'Autre chose ?',
    talkToHuman: 'Parler à quelqu’un',
    humanReply:
      'Celle-ci, une personne y répondra mieux — quelqu’un est de garde et va s’en occuper.',
    openSupport: 'Écrire à l’assistance',
    readFull: 'Voir tout le centre d’aide',
    automated:
      'Réponses automatiques issues de notre centre d’aide. Une personne est toujours à un clic.',
    transcriptTitle: 'Les questions qu’on nous pose vraiment',
  },

  search: {
    open: 'Rechercher',
    label: 'Rechercher sur le site',
    placeholder: 'Cherchez des pages, des r\u00e9ponses et des services\u2026',
    close: 'Fermer la recherche',
    clear: 'Effacer la recherche',
    empty: 'Tapez pour chercher des pages, des r\u00e9ponses et des services.',
    noResults: (query: string) => `Aucun r\u00e9sultat pour \u00ab\u202f${query}\u202f\u00bb.`,
    noResultsHint: 'Essayez le nom d\u2019un service, une question ou une r\u00e9f\u00e9rence de commande.',
    results: (count: number) => (count === 1 ? '1 r\u00e9sultat' : `${count} r\u00e9sultats`),
    groupActions: 'Aller \u00e0',
    groupPages: 'Pages',
    groupServices: 'Services',
    groupFaq: 'R\u00e9ponses',
    trackOrder: (ref: string) => `Suivre la commande ${ref}`,
    trackOrderHint: 'Ouvre le suivi avec cette r\u00e9f\u00e9rence d\u00e9j\u00e0 saisie',
    hintMove: 'pour naviguer',
    hintOpen: 'pour ouvrir',
    hintClose: 'pour fermer',
    pages: {
      home: { label: 'Accueil', keywords: 'accueil page principale home global fut services' },
      order: { label: 'Acheter des coins', keywords: 'acheter coins pieces commande prix tarif combien paiement plateforme pc ps5 playstation xbox million trading' },
      boosting: { label: 'Boosting', keywords: 'boosting boost champs rivals week-end league victoires division rang prix tarif combien' },
      coaching: { label: 'Coaching', keywords: 'coaching coach cours lecons entrainement seance reserver progresser prix tarif combien horaire' },
      rewards: { label: 'R\u00e9compenses', keywords: 'recompenses points fidelite paliers remise gagner depenser cagnotte' },
      track: { label: 'Suivre la commande', keywords: 'suivi statut commande reference livraison ou est ma commande' },
      help: { label: 'Centre d\u2019aide', keywords: 'aide faq faqs questions reponses garantie bannissement remboursement' },
      support: { label: 'Assistance', keywords: 'assistance support contact message e-mail probleme reclamation' },
      account: { label: 'Mon compte', blurb: 'Tes commandes, ton solde de points et tes informations.', keywords: 'compte profil mes commandes historique points solde parametres' },
      login: { label: 'Se connecter', blurb: 'Connecte-toi, ou crée un compte pour commencer à gagner des points.', keywords: 'connexion se connecter login inscription creer un compte mot de passe' },
      cards: { label: 'Cartes joueurs', blurb: 'Les cartes joueurs ne sont pas encore ouvertes. Voici ce qui arrive.', keywords: 'cartes joueurs icones bientot disponible' },
      terms: { label: 'Conditions de service', blurb: 'Le contrat : livraison, garanties, remboursements et ce qui est définitif.', keywords: 'conditions legal contrat remboursement politique cgv cgu' },
      privacy: { label: 'Politique de confidentialit\u00e9', blurb: 'Ce que nous collectons, combien de temps, et comment le faire supprimer.', keywords: 'confidentialite legal donnees informations personnelles cookies rgpd' },
      aml: { label: 'LCB-FT et KYC', blurb: 'Comment nous vérifions l’identité et pourquoi nous devons parfois demander.', keywords: 'aml kyc lcb-ft legal blanchiment identite verification' },
    },
  },
  catalog: {
    services: {
      TRADING_SERVICE: 'Service de trading sécurisé',
      BOOST_CHAMPS: 'Boosting Champs',
      BOOST_RIVALS: 'Boosting Rivals',
      COACHING: 'Cours FUT',
      CARDS: 'Cartes joueurs',
    },
    variants: {
      WINS_9: '9 victoires · Champion II',
      WINS_10: '10 victoires · Champion I',
      WINS_11: '11 victoires · Elite V · Rank 5',
      WINS_12: '12 victoires · Elite IV · Rank 4',
      WINS_13: '13 victoires · Elite III · Rank 3',
      WINS_14: '14 victoires · Elite II · Rank 2',
      WINS_15: '15 victoires · Elite I · Rank 1',
      WINS_EXTRA_8: '+8 victoires supplémentaires',
      DIV_5_TO_4: 'De la Division 5 à la 4',
      DIV_4_TO_3: 'De la Division 4 à la 3',
      DIV_3_TO_2: 'De la Division 3 à la 2',
      DIV_2_TO_1: 'De la Division 2 à la 1',
      DIV_1_TO_ELITE: 'De la Division 1 à Elite',
      SINGLE_SESSION: 'Séance unique · 1 heure',
      MONTHLY_6_SESSIONS: '6 séances × 40 minutes',
    },
    millions: (qty: string): string => `${qty} M`,
    lines: {
      base: (service: string, detail: string): string => `${service} — ${detail}`,
      marketTax: (pct: string): string => `Taxe du marché des transferts EA (${pct})`,
      gatewayFee: (pct: string): string => `Frais de traitement du paiement (${pct})`,
      walletRedemption: (points: number): string => `${points} points utilisés`,
      coupon: (code: string): string => `Code promo ${code}`,
      referral: (code: string): string => `Code créateur ${code}`,
      tierDiscount: 'Remise membre',
    },
  },
}

export default fr
