# Prototype Android ReviewOnce

Ce prototype fournit la coque locale nécessaire à la synchronisation en un clic :

- ReviewOnce et Letterboxd s’ouvrent dans la même WebView ;
- la connexion Letterboxd est faite manuellement une seule fois ;
- les cookies sont persistés par le stockage privé de WebView et vidés sur disque à chaque pause ;
- aucune session ni aucun mot de passe n’est envoyé au serveur ReviewOnce ;
- SQLite conserve localement l’état de la collection et les clés d’actions déjà exécutées.
- le bouton `Actualiser` parcourt la collection, la watchlist, les critiques et le journal du profil renseigné dans ReviewOnce, puis remplace la copie locale uniquement si toute la lecture aboutit.

L’écriture automatique dans le formulaire Letterboxd n’est pas encore activée. La prochaine étape est un connecteur au premier plan qui reçoit une action ReviewOnce, ouvre la fiche TMDB exacte et vérifie chaque étape avant validation.

## Construire

Ouvrir `apps/android` dans Android Studio ou exécuter le workflow GitHub `Android prototype`. Le fichier debug produit est `app/build/outputs/apk/debug/app-debug.apk`.
