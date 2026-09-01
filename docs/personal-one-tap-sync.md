# Synchronisation personnelle en un clic

## Objectif

Après une note ou une critique sur SensCritique, ReviewOnce détecte la différence puis publie le log, la note, la date et la critique sur Letterboxd avec une seule confirmation.

## Architecture recommandée

Le prototype personnel doit être une APK Android installée directement, avec un connecteur Letterboxd local :

1. l’utilisateur se connecte lui-même à Letterboxd une fois dans une WebView dédiée ;
2. la session reste dans le stockage privé et chiffré de l’application ;
3. ReviewOnce détecte et prépare les différences sans écrire silencieusement ;
4. le bouton « Synchroniser » ouvre la fiche exacte grâce à TMDB, remplit le formulaire Letterboxd et le valide au premier plan ;
5. un journal idempotent conserve la preuve locale de chaque valeur publiée et empêche les doublons ;
6. si le formulaire change ou si Letterboxd demande une vérification, l’application s’arrête et demande une intervention au lieu de deviner.

La session, les cookies et le mot de passe ne doivent jamais être envoyés au serveur ReviewOnce, enregistrés dans Git ou inclus dans les journaux de diagnostic.

## Étapes de livraison

- **Phase 1 — prototype local** : connexion manuelle, synchronisation d’un film au premier plan, note et date, puis critique.
- **Phase 2 — lot en un clic** : file d’actions sélectionnables, reprise après erreur et confirmation finale.
- **Phase 3 — détection en arrière-plan** : WorkManager cherche les changements SensCritique et affiche une notification ; l’écriture reste explicite.
- **Phase 4 — API officielle** : remplacement du connecteur WebView par OAuth dès que Letterboxd accorde l’accès.

## Autres options évaluées

- Une extension Firefox Android peut remplir le site avec la session du navigateur. Elle est plus rapide à prototyper, mais oblige à utiliser Firefox et offre une expérience moins intégrée.
- AccessibilityService et UI Automator ne conviennent pas : ils donnent des droits trop larges ou sont destinés aux tests.
- Les points d’accès privés de Letterboxd, les cookies envoyés au serveur et les clés récupérées dans une autre application sont exclus pour des raisons de sécurité, de fiabilité et d’autorisation.

## Limites

L’automatisation WebView est expérimentale. Elle dépend de l’interface Letterboxd, peut être bloquée et n’est pas un substitut contractuel à l’API officielle. Elle doit être activée seulement par son propriétaire, sur son propre compte, avec une possibilité d’arrêt immédiat.
