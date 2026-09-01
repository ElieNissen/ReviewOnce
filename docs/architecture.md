# Architecture de ReviewOnce

ReviewOnce sépare quatre responsabilités afin que l’interface web puisse devenir une application Android sans réécrire la logique produit.

## Couches

- src/domain : modèles et comparaison des bibliothèques, sans dépendance à React ou à une plateforme.
- app/api : adaptateurs temporaires vers les pages publiques de SensCritique et Letterboxd.
- app/page.tsx : interface et état local de la PWA.
- Future apps/mobile : coque Android réutilisant la logique de src/domain.

## Direction mobile

La première version APK pourra embarquer la PWA avec Capacitor. Une automatisation plus avancée devra rester locale sur le téléphone : session utilisateur dans une WebView sécurisée, stockage chiffré Android et file d’actions explicite. Aucun identifiant SensCritique ou Letterboxd ne doit transiter par le serveur.

## Cibles de synchronisation

Les cibles partagent le contrat `LetterboxdTarget` de `src/domain/letterboxd-target.ts` :

- `official-import` : disponible maintenant, génère les deux fichiers officiels journal et watchlist ;
- `official-api` : cible principale, OAuth et écritures directes après approbation Letterboxd ;
- `local-session` : expérimental, uniquement sur l’appareil et désactivé par défaut.

La cible officielle doit toujours être préférée. Une session locale ne doit jamais être envoyée au serveur ni utiliser des clés récupérées dans un autre projet.

## File de synchronisation

Le moteur évoluera vers des connecteurs interchangeables :

1. lecture normalisée des deux comptes ;
2. comparaison champ par champ ;
3. file des différences seulement ;
4. résolution manuelle des correspondances ambiguës ;
5. écriture par import officiel, OAuth ou connecteur local autorisé ;
6. journal idempotent empêchant une double publication.

Chaque action future devra posséder une clé stable composée du film, du champ et de la valeur source. Cette idempotence est la garantie technique du « une seule fois ».

Le mode Android utilisera WorkManager uniquement pour détecter et préparer des changements. L’écriture silencieuse en arrière-plan ne sera activée que si l’API officielle l’autorise ; une WebView ou session locale reste un mode de premier plan, explicite et expérimental.
