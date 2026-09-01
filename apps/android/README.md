# Prototype Android ReviewOnce

Ce prototype fournit la coque locale nécessaire à la synchronisation en un clic :

- ReviewOnce et Letterboxd s’ouvrent dans la même WebView ;
- la connexion Letterboxd est faite manuellement une seule fois ;
- les cookies sont persistés par le stockage privé de WebView et vidés sur disque à chaque pause ;
- aucune session ni aucun mot de passe n’est envoyé au serveur ReviewOnce ;
- SQLite conserve localement l’état de la collection et les clés d’actions déjà exécutées.
- le bouton `Actualiser` parcourt la collection, la watchlist, les critiques et le journal du profil renseigné dans ReviewOnce, puis remplace la copie locale uniquement si toute la lecture aboutit.

Dans l’APK, le bouton principal de ReviewOnce transmet une file locale au connecteur Android. Après une confirmation globale, le connecteur ouvre la fiche TMDB exacte et utilise le formulaire Letterboxd de la session courante. Pour éviter tout doublon pendant le prototype, seules les nouvelles entrées entièrement absentes sont publiées automatiquement ; la modification d’une entrée existante et la watchlist restent en attente tant qu’elles ne sont pas validées sur un vrai compte de test.

## Construire

Ouvrir `apps/android` dans Android Studio ou exécuter le workflow GitHub `Android prototype`. Le fichier debug produit est `app/build/outputs/apk/debug/app-debug.apk`.
