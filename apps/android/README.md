# Prototype Android ReviewOnce

Ce prototype fournit la coque locale nécessaire à la synchronisation en un clic :

- ReviewOnce est l’unique interface visible ; la couche Android exécute les opérations locales en arrière-plan ;
- la connexion Letterboxd est intégrée à l’onboarding ReviewOnce et n’est faite manuellement qu’une seule fois ;
- les cookies sont persistés par le stockage privé de WebView et vidés sur disque à chaque pause ;
- aucune session ni aucun mot de passe n’est envoyé au serveur ReviewOnce ;
- SQLite conserve localement l’état de la collection et les clés d’actions déjà exécutées.
- le bouton unique `Actualiser` parcourt la collection, la watchlist, les critiques et le journal, puis lance directement la comparaison SensCritique ;
- le profil Letterboxd détecté à la connexion est conservé localement et restauré automatiquement.

Dans l’APK, le bouton principal de ReviewOnce transmet une file locale au connecteur Android. Après une confirmation globale, le connecteur ouvre la fiche TMDB exacte et utilise le formulaire Letterboxd de la session courante. Pour éviter tout doublon pendant le prototype, seules les nouvelles entrées entièrement absentes sont publiées automatiquement ; la modification d’une entrée existante et la watchlist restent en attente tant qu’elles ne sont pas validées sur un vrai compte de test.

## Construire

Ouvrir `apps/android` dans Android Studio ou exécuter le workflow GitHub `Android prototype`. Le fichier debug produit est `app/build/outputs/apk/debug/app-debug.apk`.
