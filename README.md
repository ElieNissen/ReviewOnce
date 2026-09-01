# ReviewOnce

ReviewOnce détecte les films, notes et critiques présents sur SensCritique mais incomplets sur Letterboxd. L’application n’affiche que les différences utiles afin d’éviter la double saisie.

## Fonctionnement actuel

- lecture des profils publics SensCritique et Letterboxd ;
- comparaison locale champ par champ ;
- détection des films, notes et critiques manquants ;
- transfert assisté vers Letterboxd, sans import CSV ;
- résolution des films par identifiants SensCritique, Wikidata et TMDB ;
- score de confiance et choix manuel pour les correspondances ambiguës ;
- cache et limitation des appels pour respecter les plateformes.

ReviewOnce ne demande pas de connexion ChatGPT et ne stocke aucun identifiant de plateforme.

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

La logique métier indépendante se trouve dans src/domain. Les connecteurs web sont exposés dans app/api et l’interface PWA dans app. Voir docs/architecture.md pour la trajectoire APK et l’automatisation locale.

## Limites

SensCritique et Letterboxd ne fournissent pas d’API publique complète adaptée à ce cas. Les connecteurs actuels lisent leurs pages publiques et peuvent être affectés par des changements de structure ou des limitations temporaires. Une écriture automatique sur Letterboxd nécessitera une exécution locale et une validation explicite de l’utilisateur.
