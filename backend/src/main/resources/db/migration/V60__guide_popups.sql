-- =============================================================================
--  V60: the guide's seven popups, as DRAFTS for the client (2026-10-21)
--
--  New on popup_campaign:
--    trigger_type DELAY_OR_SCROLL  whichever comes first: trigger_value seconds, or
--                                  trigger_scroll percent of the page
--    exclude_checkout              never on a checkout page
--    preset                        which of the guide's popups this is. Three never carry a
--                                  button (COINS_OFFER, COINS_CHECKOUT, CHAMPS_CHECKOUT) and
--                                  the admin refuses one; MEMBERSHIP shows the live tier
--                                  ladder from the catalogue, never from an image.
--
--  The seven are DRAFTS, switched off, with no images: nothing shows until the client adds
--  an image, publishes and switches one on. The copy is the guide's, word for word, with
--  Spanish (tú) and French (tu). No promoter code, price or statistic is written here:
--  "{pointValue}" in the membership popup is filled in by the storefront from the live
--  point value, and the tiers and their discounts come from the catalogue.
--
--  The homepage entry popup's headline and body are placeholders: the guide leaves them to
--  the client.
-- =============================================================================

ALTER TABLE popup_campaign DROP CONSTRAINT popup_campaign_trigger_ck;
ALTER TABLE popup_campaign
    ADD CONSTRAINT popup_campaign_trigger_ck
        CHECK (trigger_type IN ('PAGE_LOAD', 'DELAY', 'SCROLL', 'DELAY_OR_SCROLL', 'STEP')),
    ADD COLUMN trigger_scroll   INT,
    ADD COLUMN exclude_checkout BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN preset           TEXT,
    ADD CONSTRAINT popup_campaign_preset_ck CHECK (preset IS NULL OR preset IN (
        'HOME_ENTRY', 'COINS_OFFER', 'COINS_CHECKOUT', 'CHAMPS_LISTING', 'CHAMPS_CHECKOUT',
        'MEMBERSHIP', 'REWARDS_REMINDER'));

-- 1. Homepage entry: centred, Home, once per session. Words are placeholders for the client.
INSERT INTO popup_campaign (public_id, preset, name, type, copy, buttons, target_pages, trigger_type, frequency, priority)
VALUES ('pop_guide_home', 'HOME_ENTRY', '1 · Homepage entry', 'CENTER_MODAL',
$j$ {"en": {"headline": "WELCOME TO GLOBAL FUT SERVICES", "body": "EA FC coins, Champs boosting and 1-to-1 coaching, all in one place."},
    "es": {"headline": "TE DAMOS LA BIENVENIDA A GLOBAL FUT SERVICES", "body": "Monedas de EA FC, boosting de Champs y coaching individual, todo en un solo sitio."},
    "fr": {"headline": "BIENVENUE CHEZ GLOBAL FUT SERVICES", "body": "Pièces EA FC, boosting Champs et coaching individuel, tout au même endroit."}}$j$,
$j$[{"label": {"en": "Explore Boosting", "es": "Explorar boosting", "fr": "Découvrir le boosting"}, "destination": "/boosting"},
    {"label": {"en": "Buy Coins", "es": "Comprar monedas", "fr": "Acheter des pièces"}, "destination": "/order"}]$j$,
'["HOME"]', 'PAGE_LOAD', 'SESSION', 100);

-- 2. Coins offer: bottom-right, Coins, after 8 s or 40% scroll, once a day, no button.
INSERT INTO popup_campaign (public_id, preset, name, type, copy, target_pages, trigger_type, trigger_value, trigger_scroll,
                            frequency, frequency_days, priority)
VALUES ('pop_guide_coins', 'COINS_OFFER', '2 · Coins offer', 'BOTTOM_RIGHT',
$j$ {"en": {"headline": "COIN PRICES JUST DROPPED!", "body": "Get the best rates on FC coins right now.",
           "referral": "Don't forget to use your referral code for 5% OFF.", "trust": "You are covered with our 100% Safety Policy."},
    "es": {"headline": "¡LOS PRECIOS DE LAS MONEDAS ACABAN DE BAJAR!", "body": "Consigue ahora mismo las mejores tarifas en monedas de FC.",
           "referral": "No olvides usar tu código de referido para un 5 % DE DESCUENTO.", "trust": "Estás cubierto por nuestra Política de Seguridad 100 %."},
    "fr": {"headline": "LES PRIX DES PIÈCES VIENNENT DE BAISSER !", "body": "Profite dès maintenant des meilleurs tarifs sur les pièces FC.",
           "referral": "N’oublie pas d’utiliser ton code de parrainage pour 5 % DE RÉDUCTION.", "trust": "Tu es couvert par notre Politique de sécurité 100 %."}}$j$,
'["COINS"]', 'DELAY_OR_SCROLL', 8, 40, 'DAYS', 1, 50);

-- 3. Coins checkout: bottom-right, at coins:continue, no button.
INSERT INTO popup_campaign (public_id, preset, name, type, copy, target_pages, trigger_type, trigger_event, frequency, priority)
VALUES ('pop_guide_coins_checkout', 'COINS_CHECKOUT', '3 · Coins checkout', 'BOTTOM_RIGHT',
$j$ {"en": {"headline": "LIVE ORDER TRACKING & LIVE CHAT", "body": "After placing your order, you will get live order tracking and live chat support so you can stay updated and get help whenever you need it."},
    "es": {"headline": "SEGUIMIENTO DEL PEDIDO Y CHAT EN DIRECTO", "body": "Después de hacer tu pedido, tendrás seguimiento del pedido en directo y soporte por chat en directo para estar al día y recibir ayuda siempre que la necesites."},
    "fr": {"headline": "SUIVI DE COMMANDE ET CHAT EN DIRECT", "body": "Après avoir passé ta commande, tu bénéficieras du suivi de commande en direct et d’une assistance par chat en direct pour rester informé et obtenir de l’aide dès que tu en as besoin."}}$j$,
'["COINS_CHECKOUT"]', 'STEP', 'coins:continue', 'SESSION', 50);

-- 4. Champs listing: bottom-right, Champs listing, after 8 s or 40% scroll, one button.
INSERT INTO popup_campaign (public_id, preset, name, type, copy, buttons, target_pages, trigger_type, trigger_value,
                            trigger_scroll, frequency, priority)
VALUES ('pop_guide_champs', 'CHAMPS_LISTING', '4 · Champs listing', 'BOTTOM_RIGHT',
$j$ {"en": {"headline": "GET YOUR CHAMPS RANK DONE!", "body": "Book Rank 1, Rank 2 or Rank 3 with our boosting service.",
           "referral": "Don't forget to use your referral code for 5% OFF."},
    "es": {"headline": "¡CONSIGUE TU RANGO EN CHAMPS!", "body": "Reserva Rango 1, Rango 2 o Rango 3 con nuestro servicio de boosting.",
           "referral": "No olvides usar tu código de referido para un 5 % DE DESCUENTO."},
    "fr": {"headline": "DÉCROCHE TON RANG EN CHAMPS !", "body": "Réserve le Rang 1, le Rang 2 ou le Rang 3 avec notre service de boosting.",
           "referral": "N’oublie pas d’utiliser ton code de parrainage pour 5 % DE RÉDUCTION."}}$j$,
$j$[{"label": {"en": "Explore Champs Boosting", "es": "Ver el boosting de Champs", "fr": "Découvrir le boosting Champs"}, "destination": "/boosting"}]$j$,
'["CHAMPS_LISTING"]', 'DELAY_OR_SCROLL', 8, 40, 'SESSION', 50);

-- 5. Champs checkout: bottom-right, at champs:checkout, no button. The guide's lines in its
--    order: the payment sentence sits with the Safety Policy line, after the referral line.
INSERT INTO popup_campaign (public_id, preset, name, type, copy, target_pages, trigger_type, trigger_event, frequency, priority)
VALUES ('pop_guide_champs_checkout', 'CHAMPS_CHECKOUT', '5 · Champs checkout', 'BOTTOM_RIGHT',
$j$ {"en": {"headline": "ONE STEP CLOSER TO GET YOUR RANK!", "body": "Book Rank 1, Rank 2 or Rank 3 with our boosting service.",
           "referral": "Don't forget to use your referral code for 5% OFF.",
           "trust": "After you complete the payment, you will be connected to a booster on our team to start your order.\n\nYou are covered by our 100% Safety Policy."},
    "es": {"headline": "¡UN PASO MÁS CERCA DE TU RANGO!", "body": "Reserva Rango 1, Rango 2 o Rango 3 con nuestro servicio de boosting.",
           "referral": "No olvides usar tu código de referido para un 5 % DE DESCUENTO.",
           "trust": "Cuando completes el pago, te pondremos en contacto con un booster de nuestro equipo para empezar tu pedido.\n\nEstás cubierto por nuestra Política de Seguridad 100 %."},
    "fr": {"headline": "PLUS QU’UNE ÉTAPE AVANT TON RANG !", "body": "Réserve le Rang 1, le Rang 2 ou le Rang 3 avec notre service de boosting.",
           "referral": "N’oublie pas d’utiliser ton code de parrainage pour 5 % DE RÉDUCTION.",
           "trust": "Une fois le paiement effectué, tu seras mis en relation avec un booster de notre équipe pour lancer ta commande.\n\nTu es couvert par notre Politique de sécurité 100 %."}}$j$,
'["CHAMPS_CHECKOUT"]', 'STEP', 'champs:checkout', 'SESSION', 50);

-- 6. Membership explainer: centred, Rewards (the client adds pages). The tier ladder is drawn
--    from the catalogue under the body; "{pointValue}" is the live point value.
INSERT INTO popup_campaign (public_id, preset, name, type, copy, target_pages, trigger_type, frequency, priority)
VALUES ('pop_guide_membership', 'MEMBERSHIP', '6 · Membership explainer', 'CENTER_MODAL',
$j$ {"en": {"headline": "UNLOCK MORE BENEFITS", "body": "The more you spend, the higher your tier. Get bigger discounts and exclusive perks with GFS Membership.",
           "trust": "Eligible orders earn points. Points are worth {pointValue} each at checkout."},
    "es": {"headline": "DESBLOQUEA MÁS VENTAJAS", "body": "Cuanto más gastas, más alto es tu nivel. Consigue mayores descuentos y ventajas exclusivas con la membresía de GFS.",
           "trust": "Los pedidos que cumplen los requisitos suman puntos. Cada punto vale {pointValue} al pagar."},
    "fr": {"headline": "DÉBLOQUE PLUS D’AVANTAGES", "body": "Plus tu dépenses, plus ton niveau monte. Profite de réductions plus importantes et d’avantages exclusifs avec l’adhésion GFS.",
           "trust": "Les commandes éligibles rapportent des points. Chaque point vaut {pointValue} au paiement."}}$j$,
'["REWARDS"]', 'PAGE_LOAD', 'SESSION', 60);

-- 7. Rewards reminder: bottom-right, chosen pages (Home to start), after 8 s or 40% scroll,
--    one button, never during checkout.
INSERT INTO popup_campaign (public_id, preset, name, type, copy, buttons, target_pages, trigger_type, trigger_value,
                            trigger_scroll, exclude_checkout, frequency, priority)
VALUES ('pop_guide_rewards', 'REWARDS_REMINDER', '7 · Rewards reminder', 'BOTTOM_RIGHT',
$j$ {"en": {"headline": "MORE ORDERS, BIGGER REWARDS", "body": "Earn points with every eligible order and unlock higher discounts.",
           "trust": "Earn points · Higher discounts · Exclusive perks"},
    "es": {"headline": "MÁS PEDIDOS, MAYORES RECOMPENSAS", "body": "Gana puntos con cada pedido que cumpla los requisitos y desbloquea descuentos mayores.",
           "trust": "Gana puntos · Descuentos mayores · Ventajas exclusivas"},
    "fr": {"headline": "PLUS DE COMMANDES, PLUS DE RÉCOMPENSES", "body": "Gagne des points à chaque commande éligible et débloque des réductions plus importantes.",
           "trust": "Gagne des points · Réductions plus importantes · Avantages exclusifs"}}$j$,
$j$[{"label": {"en": "Explore Rewards", "es": "Ver recompensas", "fr": "Découvrir les récompenses"}, "destination": "/rewards"}]$j$,
'["HOME"]', 'DELAY_OR_SCROLL', 8, 40, TRUE, 'SESSION', 40);

INSERT INTO popup_change (campaign_id, action, detail)
SELECT id, 'CREATED', 'From the guide (V60): draft, switched off, no image' FROM popup_campaign WHERE preset IS NOT NULL;
