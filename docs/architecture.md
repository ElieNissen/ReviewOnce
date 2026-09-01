# Architecture de ReviewOnce

ReviewOnce sépare quatre responsabilités afin que l’interface web puisse devenir une application Android sans réécrire la logique produit.

## Couches

- src/domain : modèles et comparaison des bibliothèques, sans dépendance à React ou à une plateforme.
- app/api : adaptateurs temporaires vers les pages publiques de SensCritique et Letterboxd.
- app/page.tsx : interface et état local de la PWA.
- Future apps/mobile : coque Android réutilisant la logique de src/domain.

## Direction mobile

La première version APK pourra embarquer la PWA avec Capacitor. Une automatisation plus avancée devra rester locale sur le téléphone : session utilisateur dans une WebView sécurisée, stockage chiffré Android et file d’actions explicite. Aucun identifiant SensCritique ou Letterboxd ne doit transiter par le serveur.

## Direction synchronisation

Le moteur évoluera vers des connecteurs interchangeables :

1. lecture normalisée des deux comptes ;
2. comparaison champ par champ ;
3. file des différences seulement ;
4. résolution manuelle des correspondances ambiguës ;
5. écriture assistée ou locale ;
6. journal idempotent empêchant une double publication.

Chaque action future devra posséder une clé stable composée du film, du champ et de la valeur source. Cette idempotence est la garantie technique du « une seule fois ».
