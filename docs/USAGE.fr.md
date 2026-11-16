# Guide d'utilisation

**Langues :** [English](USAGE.md) · [日本語](USAGE.ja.md) · [简体中文](USAGE.zh-Hans.md) · [Español](USAGE.es.md) · [हिन्दी](USAGE.hi.md) · [العربية](USAGE.ar.md) · [한국어](USAGE.ko.md) · [Русский](USAGE.ru.md) · Français

Cette application fait tourner un **nœud complet** du Quantus Network (réseau principal QTC) sur un téléphone Android. Il synchronise la chaîne, vérifie lui-même chaque bloc et relaie les données aux pairs. Par défaut, il fonctionne comme non-validateur ; il peut aussi miner, en option désactivée par défaut (voir **Minage** ci-dessous). Il n'a **pas de portefeuille** et ne touche jamais aux phrases de récupération — le seul secret qu'il puisse conserver est l'Inner Hash des récompenses de minage que vous collez vous-même.

> État : v4.2.0 publiée (2026-11-16), la version actuelle — l'écran principal est désormais une liste sous la carte d'état (lignes pour le relevé du jour, le minage, le prix du QTC, les détails du nœud, la trajectoire d'activité et les dons ; le prix et l'adresse de don s'ouvrent en feuilles, et la récupération du prix peut être désactivée) ; les Réglages forment un centre de pages, chaque ligne affichant sa valeur actuelle ; l'onglet membre défile et sa carte a un rapport déclaré de 1.42:1 ; les changements d'écran et les pressions ont un bref mouvement et un retour tactile ; et l'icône de notification est une silhouette monochrome (v4.1.0, également publiée le 2026-11-16, avait ajouté la gestion du stockage et un relevé d'activité quotidien conservé sur l'appareil). L'APK signé est disponible sur [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) avec sa somme de contrôle SHA-256. Les versions de test précédentes étaient partagées directement par le mainteneur.

## Prérequis

- Android 12 ou plus récent, appareil arm64
- Espace libre : la base de données du nœud est élaguée (seuls les 256 derniers blocs sont conservés), donc elle reste petite. Mesuré sur un Pixel 9a après une synchronisation complète (2026-11-05) : 1,30 Go au total (96 Mo d'application + 1,20 Go de données du nœud). À titre indicatif, gardez au moins environ 3 Go libres
- Une connexion internet. Le Wi-Fi est fortement recommandé pour la première synchronisation (voir ci-dessous)

Vérifié jusqu'ici : **Pixel 9a sous GrapheneOS**. Les autres appareils devraient fonctionner, mais ils ne sont pas vérifiés. Si vous l'essayez sur un autre modèle, signalez le résultat (que cela fonctionne ou non) dans une Issue GitHub — la liste des appareils vérifiés grandit grâce à ces rapports.

## Installation

1. Récupérez le fichier APK sur [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) (vérifiez la somme SHA-256 publiée avec la version avant d'installer).
2. Ouvrez l'APK avec votre application Fichiers et autorisez l'installation depuis cette source quand Android le demande.
3. Installez, puis ouvrez **Quantus Node**.

## Premier lancement et synchronisation initiale

1. (Facultatif) Réglez d'abord le **nom d'affichage** et l'option de **télémétrie** (voir Réglages). Les changements de réglages nécessitent un redémarrage du nœud (Arrêter → Démarrer) pour s'appliquer.
2. Touchez **Démarrer le nœud**.
3. L'état affiche **Synchronisation** et le meilleur bloc grimpe vers le bloc le plus haut. Sur un Pixel 9a, la première synchronisation depuis la genèse avance à environ 50 blocs par seconde — comptez de l'ordre de **2 heures**. Une fois à jour, un nouveau bloc arrive environ toutes les 12 secondes et l'état passe à synchronisé.
4. Pendant la première synchronisation, gardez si possible le téléphone **en charge et en Wi-Fi**. La synchronisation en données mobiles fonctionne, mais elle télécharge beaucoup de données et consomme sensiblement plus de batterie (mesuré : environ 11 % en 30 minutes en synchronisation sur données mobiles). Une fois la synchronisation terminée, la charge permanente est faible.

Pas besoin de garder l'application ouverte. Le nœud fonctionne comme un service de premier plan : il continue écran éteint ou pendant que vous utilisez d'autres applications, et affiche une notification permanente pendant son fonctionnement. Si vous l'arrêtez puis le redémarrez, la synchronisation reprend où elle s'était arrêtée — la chaîne téléchargée reste sur l'appareil.

## Ce que montrent les écrans

L'application compte trois onglets en bas : Accueil, Connexion et Carte de membre. Les Détails du nœud et les Réglages s'ouvrent depuis l'accueil.

- **Accueil** — le meilleur bloc du nœud et son état (Arrêté / Démarrage / Synchronisation / Synchronisé / Échec) et le bouton principal. Pendant que le nœud tourne, l'écran affiche aussi le nombre de pairs connectés, la durée de fonctionnement de cette session, ainsi que le prix actuel du QTC. Si un démarrage échoue, l'accueil l'affiche comme un échec avec un bouton de nouvel essai, plutôt que comme un arrêt ordinaire ; si un réglage que vous avez modifié n'est pas encore en vigueur, l'accueil affiche un lien pour le vérifier
- **Connexion** — une image vivante du champ réseau de votre nœud : les pairs auxquels il est connecté et les échos de ses propres points de contrôle enregistrés. Elle montre des relations, pas des chiffres pour se comparer — il n'y a aucun classement
- **Détails du nœud** — meilleur bloc / bloc le plus haut (où en est votre nœud par rapport à la pointe du réseau qu'il connaît), l'écart de synchronisation, les pairs et la durée de fonctionnement, ainsi que la section **Diagnostics** : code de sortie, notes du service, l'historique de chaque tentative de démarrage et **Partager le journal** (voir ci-dessous). Pendant que le nœud est arrêté, cet écran affiche votre trace cumulée plutôt que les valeurs de la session précédente
- **Carte de membre** — votre carte de membre : votre numéro de membre (l'identifiant de votre appareil) et trois chiffres de votre journal local — la date de votre premier point de contrôle, votre nombre de points de contrôle et votre plus longue série de jours. Le journal est stocké uniquement sur cet appareil ; il n'est jamais envoyé et n'a aucune valeur monétaire — c'est simplement la trace de votre contribution, et la montrer ou non vous appartient
- **Réglages** — les réglages ci-dessous ; la version de l'application est affichée tout en bas

## Réglages

- **Apparaître dans la télémétrie** (désactivé par défaut) — quand c'est activé, le nom de votre nœud et son état en direct deviennent publiquement visibles dans la télémétrie officielle sur telemetry.quantus.com. Ne l'activez que si vous voulez figurer dans la liste. S'applique au prochain démarrage du nœud (Arrêter → Démarrer).
- **Nom d'affichage** — le nom qu'utilise votre nœud (également affiché dans la télémétrie si elle est activée), qui se modifie dans une boîte de dialogue. Laissez vide pour utiliser le nom par défaut `quantus-android-node`. S'applique au prochain démarrage du nœud.
- **Langue** — suivre le réglage de l'appareil, ou choisir parmi 9 langues (japonais, anglais, chinois simplifié, espagnol, hindi, arabe, coréen, russe, français). S'applique immédiatement.
- **Enregistrer mon activité (DePIN)** (désactivé par défaut) — quand c'est activé, l'application conserve sur cet appareil un journal signé du temps de fonctionnement et du travail de relais du nœud (un point de contrôle toutes les 10 minutes). Rien n'est envoyé nulle part de lui-même ; depuis les Réglages, vous pouvez exporter le journal sous forme de fichier de preuves et le partager vous-même. La désactivation arrête l'enregistrement ; le journal reste sur l'appareil
- **Verrouiller les réglages** — verrouille les réglages ci-dessus (télémétrie, nom d'affichage, langue et enregistrement d'activité) pour qu'ils ne changent pas par accident pendant que le téléphone reste à faire tourner le nœud. C'est uniquement une protection contre les manipulations involontaires : il n'y a pas de code PIN, et le démarrage ou l'arrêt du nœud ne sont jamais verrouillés. Désactivez **Verrouiller les réglages** pour pouvoir les modifier à nouveau.
- **Minage** (désactivé par défaut) — le nœud peut aussi miner sur cet appareil, avec le mineur officiel embarqué. Il lui faut d'abord un Inner Hash de récompenses ; voir **Minage (facultatif)** ci-dessous.

Les réglages ne redémarrent jamais le nœud d'eux-mêmes. Si vous changez la télémétrie ou le nom d'affichage pendant que le nœud tourne, l'élément modifié est marqué dans les Réglages comme pas encore appliqué, et l'accueil affiche un lien pour vérifier les réglages en attente ; le changement prend effet au prochain démarrage du nœud (Arrêter → Démarrer).

**Partager le journal** se trouve dans **Détails du nœud → Diagnostics** : il partage le journal du nœud en texte, et c'est ce qu'il faut joindre quand vous signalez un problème. Avant de coller un journal dans un lieu public, vérifiez d'abord son contenu : il peut inclure l'adresse IP publique de votre nœud et des informations sur les pairs. La boîte de dialogue de partage peut masquer le nom d'affichage, les adresses des interfaces de cet appareil et les chemins privés de l'application (activé par défaut) ; l'adresse extérieure que le nœud apprend par ses pairs n'est pas masquée.

## Minage (facultatif)

L'application peut aussi miner, sur ce téléphone, avec le mineur officiel qu'elle embarque. Le minage est **désactivé par défaut**, et le nœud fonctionne exactement de la même façon lorsqu'il est désactivé. Une fois activé, le nœud démarre comme validateur, et les récompenses vous reviennent — pas à l'application.

**Avant de pouvoir activer le minage, il vous faut un Inner Hash de récompenses.** Les récompenses ne sont pas versées à une adresse ordinaire : le protocole les verse à une **adresse wormhole** dérivée d'un secret appelé Inner Hash.

Pour obtenir un Inner Hash :

- Sur un PC, exécutez la commande du nœud officiel `quantus-node key quantus --scheme wormhole`. Elle affiche trois valeurs : l'**Address** (où iront les récompenses), l'**Inner Hash** (la valeur dont cette application a besoin) et une **Secret phrase** (votre sauvegarde — conservez-la en lieu sûr, hors ligne).
- Si vous utilisez déjà le portefeuille officiel, vous pouvez dériver les mêmes valeurs depuis sa phrase de récupération : ajoutez `--words` à la même commande et saisissez la phrase lorsqu'elle est demandée — sur le PC, jamais dans cette application. Avec la même phrase de récupération, les récompenses apparaissent automatiquement dans ce portefeuille.

Ensuite, dans l'application :

1. Ouvrez **Réglages → Minage** et collez la valeur de l'**Inner Hash** (de la forme `0x` suivi de 64 chiffres hexadécimaux). Ne saisissez que l'Inner Hash — jamais une phrase de récupération ni une clé secrète : l'Inner Hash est inscrit dans chaque bloc produit par cet appareil et devient public.
2. L'application affiche l'adresse de récompenses que le nœud signale pour votre Inner Hash. Comparez-la à l'**Address** donnée par l'outil avant de vous y fier.
3. Activez le minage, puis redémarrez le nœud (Arrêter → Démarrer). Les réglages de minage s'appliquent au prochain démarrage du nœud.

**Vérifiez soigneusement l'Inner Hash avant d'enregistrer.** S'il est faux, les récompenses seront versées à cette mauvaise destination et ne pourront pas être récupérées. La valeur enregistrée reste uniquement sur cet appareil ; l'application ne l'affiche jamais à l'écran, dans les journaux ni dans les textes partagés.

**Travailleurs et chaleur.** Vous pouvez choisir 1, 2, 4 ou automatique travailleurs CPU (2 par défaut ; un cœur reste toujours libre pour le nœud lui-même). Plus de travailleurs signifie plus de chaleur et de consommation de batterie. Si le téléphone chauffe, le minage se réduit automatiquement au niveau de chaleur « modéré » de la plateforme et s'arrête pour le reste de la session au niveau « sévère » — le téléphone n'est jamais poussé à sa limite.

**Attente honnête :** au taux de hachage d'un téléphone, l'attente moyenne avant de trouver un bloc est très longue — bien plus longue que n'importe quelle session de test. Miner ici est une façon de participer, pas de gagner de l'argent.

## Au quotidien

- Le nœud ne s'arrête que lorsque vous touchez **Arrêter le nœud**, forcez l'arrêt de l'application, ou redémarrez le téléphone.
- Après un redémarrage du téléphone, ouvrez l'application et touchez à nouveau **Démarrer le nœud** — le démarrage automatique après l'allumage n'est pas encore implémenté.
- Sur certaines versions d'Android de fabricants (Samsung, Xiaomi, etc.), les économies de batterie agressives peuvent arrêter les services d'arrière-plan. Si votre nœud s'arrête tout seul, exemptez cette application de l'optimisation de la batterie dans les réglages du système.
- Un téléphone de rechange branché 24 h/24 ? Vérifiez la batterie de temps en temps — les vieilles batteries lithium peuvent gonfler. Gardez le téléphone dans un endroit ventilé, idéalement avec une limite de charge si votre appareil en propose une.

## En cas de problème

- **Le nœud se ferme tout de suite** — ouvrez **Détails du nœud → Diagnostics → Partager le journal** et envoyez le journal avec une Issue GitHub (modèle de rapport de bug). Le journal montre chaque tentative de démarrage et la raison de sa fin.
- **Les pairs restent à 0** — vérifiez la connexion, puis Arrêter → Démarrer une fois.
- **La synchronisation se bloque (votre meilleur bloc s'arrête pendant que le réseau avance)** — l'application détecte cette situation et redémarre le nœud d'elle-même (au maximum 3 fois par 24 heures). Si cela se répète, une connexion Wi-Fi stable aide ; puis Arrêter → Démarrer une fois
- **Votre nœud n'apparaît pas dans la télémétrie** — vérifiez que l'option de télémétrie est activée et que le nœud a été redémarré après l'activation. (L'apparition dans la liste est vérifiée : le nœud du mainteneur lui-même figure dans la liste officielle.)
- **Questions de stockage** — la base de données est élaguée aux 256 derniers blocs par conception. Désinstaller l'application supprime la base de données et la clé d'identité du nœud ; une réinstallation repart pour une nouvelle synchronisation avec une nouvelle identité.

Questions, rapports d'appareils et idées sont bienvenus en Issues GitHub. Pour les problèmes de sécurité, suivez [SECURITY.md](../SECURITY.md) plutôt qu'une Issue publique.
