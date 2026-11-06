# Clé de signature TEMPORAIRE

`speedbar-temp.jks` est une clé jetable, publique, utilisée seulement en attendant une vraie clé.
**Ne pas la considérer comme sûre** : n'importe qui peut signer un APK avec.

- Alias : `speedbar`
- Mot de passe (keystore et clé) : `dwWELF4VSEQB3tH51B7z7rnK`

## La remplacer
1. Générer une clé : `keytool -genkeypair -keystore release.jks -alias speedbar -keyalg RSA -keysize 2048 -validity 10000`
2. Ajouter dans Settings > Secrets and variables > Actions :
   - `SIGNING_KEYSTORE_BASE64` : sortie de `base64 -w0 release.jks`
   - `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS`
3. Supprimer ce dossier.

Changer de clé impose de désinstaller l'app une fois avant d'installer la nouvelle version.
