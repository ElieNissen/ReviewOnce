# ReviewOnce

ReviewOnce détecte les films, notes et critiques loggés sur un profil SensCritique mais incomplets sur Letterboxd. L’application n’affiche que les différences utiles afin d’éviter la double saisie.

## Fonctionnement actuel

- lecture des profils publics SensCritique et Letterboxd ;
- comparaison locale champ par champ ;
- détection des films, notes et critiques manquants ;
- import officiel en lot vers Letterboxd, sans ressaisie de notes ou critiques ;
- comparaison de la collection et de la watchlist ;
- file idempotente préparant une future synchronisation directe ;
- résolution des films par identifiants SensCritique, Wikidata et TMDB ;
- score de confiance et choix manuel pour les correspondances ambiguës ;
- cache et limitation des appels pour respecter les plateformes.

Le jeton TMDB facultatif se configure côté hébergement avec `TMDB_API_TOKEN`. Sans lui, le résolveur utilise Wikidata puis titre original + année. Aucune clé n'est envoyée au navigateur.

## Développement

Prérequis : Node.js 22.13 ou supérieur.

    npm ci
    npm run dev

Commandes utiles :

- npm run build : production ;
- npm run lint : analyse statique ;
- npm test : validation du rendu.

## Architecture

La logique métier indépendante se trouve dans src/domain. Les connecteurs web sont exposés dans app/api et l’interface PWA dans app. Voir docs/architecture.md pour la trajectoire APK et l’automatisation locale. Le dossier de demande d’accès OAuth/API Letterboxd est prêt dans docs/letterboxd-api-application.md.

## Limites

SensCritique et Letterboxd ne fournissent pas d’API publique complète ouverte à ce cas. Les connecteurs de lecture actuels peuvent être affectés par des changements de structure ou des limitations temporaires. L’import CSV est la cible officielle disponible ; la synchronisation directe sera activée uniquement après approbation OAuth/API de Letterboxd.
