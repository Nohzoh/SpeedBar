# SpeedBar

Affiche ta vitesse GPS (km/h) en temps réel dans la barre d'état Android.

Utile par exemple pour les jeux géolocalisés qui bloquent les interactions au-delà d'une certaine vitesse.

## Fonctionnement
- Service au premier plan qui lit le GPS chaque seconde.
- La vitesse est dessinée dans l'icône de la notification (barre d'état). `--` = pas de fix GPS récent.
- Bouton **Noter la vitesse** dans la notification : enregistre la vitesse du moment. La plus basse est affichée comme seuil.

## Compiler
- **Android Studio** : ouvrir le dossier, Run.
- **GitHub Actions** : chaque push compile l'APK debug, disponible dans l'onglet Actions (artefact `speedbar-debug`).

minSdk 26 (Android 8), targetSdk 35.
