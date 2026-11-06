# SpeedBar

Affiche ta vitesse GPS (km/h) en temps réel dans la barre d'état Android.

Utile par exemple pour les jeux géolocalisés qui bloquent les interactions au-delà d'une certaine vitesse.

## Fonctionnement
- Service au premier plan qui lit le GPS chaque seconde.
- La vitesse est dessinée dans l'icône de la notification (barre d'état). `--` = pas de fix GPS récent.
- Bouton **Noter la vitesse** dans la notification : enregistre la vitesse du moment. La plus basse est affichée comme seuil.

## Compiler
- **Android Studio** : ouvrir le dossier, Run.
- **GitHub Actions** : chaque push compile l'APK release signé, disponible dans l'onglet Actions (artefact `speedbar-release`).

minSdk 26 (Android 8), targetSdk 35.

## Signature
Le build release est signé avec une clé fournie via les secrets GitHub Actions :
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS`.
