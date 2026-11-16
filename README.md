# Kaddie

Application Android native (Kotlin, Jetpack Compose, Material 3) de gestion de listes de courses, 100 % hors connexion. Utilisateur unique, MVP.

## Fonctionnalités

- Plusieurs listes : création, renommage, duplication, suppression avec confirmation, compteur de produits restants.
- Produits : quantité, unité (pièce, paquet, bouteille, kg, g, L, mL), note, coche achetée, suppression avec « Annuler ».
- Regroupement par rayon : les produits à acheter sont classés par catégorie (fruits et légumes, laitiers, boissons…) dans un ordre fixe, pour faire ses courses sans allers-retours. Désactivable dans le menu ⋮ de la liste (« Afficher sans rayons »), réglage mémorisé.
- Produits habituels : quand le champ d'ajout est vide, les produits les plus achetés (hors ceux déjà dans la liste) sont proposés en puces, ajoutables en un appui.
- Modèles de listes : « Enregistrer comme modèle » (menu ⋮ d'une liste) enregistre ses produits et quantités **sur le serveur** (API `/v1/templates`) ; rien n'est conservé sur le téléphone. « Nouvelle liste » charge les modèles du compte et propose d'en partir (tous les produits « à acheter »), et permet de les supprimer. Ils demandent donc une connexion et un compte Google (message explicite sinon) ; la liste créée à partir d'un modèle, elle, est locale et synchronisée comme les autres.
- Partage : « Partager la liste » (menu ⋮) envoie le texte de ce qui reste à acheter (par rayon si le regroupement est activé) via le partage d'Android (messages, WhatsApp, e-mail…), sans compte pour le destinataire.
- Ajout par la voix : le bouton micro de la barre d'ajout ouvre la reconnaissance vocale d'Android. Une phrase peut contenir plusieurs produits, avec quantité et unité facultatives : « deux kilos de tomates, du lait et 500 grammes de farine ». Les fautes probables et les unités incompatibles sont demandées une par une, comme pour une saisie au clavier.
- Épingler un produit : appui long sur un produit (ou interrupteur dans sa fiche) pour le placer en haut de la liste, dans une section « 📌 Épinglés ». L'épinglage est synchronisé (champ `isPinned` de l'API) ; avec une API plus ancienne qui ne connaît pas ce champ, la valeur locale est conservée.
- Ajout rapide : suggestions dès 3 caractères (5 maximum), clavier maintenu ouvert après chaque ajout.
- Dictionnaire local d'environ 1 000 produits (`app/src/main/assets/dictionary.json`), enrichi automatiquement ; les produits de l'utilisateur sont conservés lors des mises à jour.
- Recherche tolérante : casse, accents, pluriels, tirets/espaces, fautes de frappe (Damerau-Levenshtein). Aucune fusion automatique sur une correspondance incertaine.
- Fusion des doublons avec conversion d'unités (500 g + 1 kg = 1,5 kg) et choix en cas d'unités incompatibles.
- Thème clair / sombre / dynamique.

## Architecture

```
app/src/main/java/com/kaddie/app/
├── domain/   modèles, TextNormalizer, ProductMatchingService, AddShoppingListItemUseCase, interfaces de dépôts
├── data/     Room (entités, DAO), dépôts, import du dictionnaire, AuthRepository (mock), API distante (mock), réglages
└── ui/       écrans Compose (listes, détail), ViewModels, thème
```

- Base Room = source de vérité ; opérations multi-étapes transactionnelles ; identifiants UUID.
- `AuthRepository` / `MockAuthRepository` : utilisateur local fictif ; future `CognitoAuthRepository`.
- `ShoppingRemoteDataSource` : point d'extension pour l'API AWS ; le mock renvoie « indisponible » (pas de fausse synchronisation).
- Injection de dépendances manuelle (`AppContainer`).

## Build

Prérequis : JDK 17+, SDK Android (compileSdk 37). Renseigner `sdk.dir` dans `local.properties`.

```sh
./gradlew :app:testPreprodDebugUnitTest   # tests unitaires
./gradlew :app:assemblePreprodDebug       # APK dans app/build/outputs/apk/preprod/debug/
```

## Synchronisation avec l'API

Room reste la source de vérité : l'interface n'attend jamais le réseau.

- **Écritures** : chaque modification locale ajoute, dans la même transaction, une opération à une file (`sync_operations`). `SyncWorker` (WorkManager, réseau requis, backoff exponentiel) la vide dans l'ordre ; les opérations relisent l'état local au moment de l'envoi, donc rejouables. Les données déjà présentes avant la synchronisation sont envoyées au premier passage.
- **Lecture** : lecture complète des listes et de leurs produits au démarrage, après les écritures et toutes les 15 min (seuls les produits personnels restent incrémentaux, par `updatedSince`). Une entité ayant des écritures en attente n'est jamais écrasée.
- **Suppressions faites sur un autre appareil** : l'app mémorise les identifiants que le serveur avait à la dernière lecture complète. Une liste ou un produit qu'il avait et n'a plus a été supprimé ailleurs, et l'est donc aussi ici (`Reconciliation`). Ce qui n'a jamais été sur le serveur (créé hors connexion, refusé par l'API) et tout ce qui a une écriture en attente sont conservés, et rien n'est supprimé si la lecture est incomplète. Pas besoin de « tombstones » côté API.
- **Erreurs** : réseau/5xx → nouvelle tentative ; 401 → file conservée, synchronisation suspendue ; 400/404/409 → opération abandonnée côté serveur seulement, jamais de perte locale ; `409 UNIT_CONFLICT` → renvoi avec `unitConflict: REPLACE` (l'état local fait foi).
- **Indicateur** : icône nuage discrète dans la barre du haut (synchronisé, en cours, en attente, hors connexion, problème) ; un appui relance la synchronisation.
- **Authentification** : connexion Google via Cognito (hosted UI), flux *authorization code + PKCE* avec AppAuth et Custom Tabs ; redirections `kaddie://auth/callback` et `kaddie://auth/logout`. Les jetons sont chiffrés (AES-GCM, clé dans l'Android Keystore) et renouvelés avec le `refresh_token` ; un 401 déclenche un renouvellement puis un seul nouvel essai. `Authorization: Bearer <access_token>` est envoyé par `BearerInterceptor`. L'app reste utilisable sans compte et hors connexion : tant qu'on n'est pas connecté, les écritures restent dans la file et sont envoyées à la connexion (changement de compte : renvoi des données locales et relecture complète). L'identifiant côté API est le `sub` du jeton.
- **Variantes de build** : `preprod` et `prod` (domaine Cognito, client, URL de l'API dans `BuildConfig`). Elles s'installent côte à côte : la préprod est `com.kaddie.app.preprod`, nommée « Kaddie préprod » (icône orange, badge rouge « PRÉPROD » dans la barre du haut), avec ses propres données et son propre compte, et son schéma de redirection OAuth est `kaddie-preprod://auth/callback` (déconnexion : `kaddie-preprod://auth/logout`). La prod garde `com.kaddie.app` et `kaddie://auth/callback`. Ces redirections doivent être déclarées dans le client Cognito correspondant (URL de rappel et de déconnexion). `./gradlew :app:assemblePreprodDebug`, `:app:testPreprodDebugUnitTest`.
- **Limite connue** : si un produit est modifié hors connexion pendant qu'un autre appareil le supprime, la modification gagne et il réapparaît.

## Build de production

1. **Clé de signature** (une seule fois, à sauvegarder hors du dépôt, par exemple dans un gestionnaire de mots de passe) :
   ```bash
   keytool -genkeypair -v -keystore ~/kaddie-release.jks -alias kaddie -keyalg RSA -keysize 4096 -validity 10000
   ```
   Perdre cette clé empêche de publier des mises à jour (hors *Play App Signing*, recommandé).
2. **Renseigner** dans `local.properties` (non versionné) :
   ```properties
   kaddie.release.storeFile=/Users/vous/kaddie-release.jks
   kaddie.release.storePassword=...
   kaddie.release.keyAlias=kaddie
   kaddie.release.keyPassword=...
   ```
3. **Construire** la variante `prod` : `./gradlew :app:bundleProdRelease` (bundle `.aab` pour le Play Store, dans
   `app/build/outputs/bundle/prodRelease/`) ou `./gradlew :app:assembleProdRelease` (APK signé, pour un test sur téléphone,
   dans `app/build/outputs/apk/prod/release/`). Sans les propriétés ci-dessus, l'APK est produit mais **non signé**.
4. La variante `prod` utilise `https://kaddie-api.cyrilmarchive.com/v1/` et le client Cognito de prod ; incrémenter
   `versionCode` (et `versionName`) à chaque publication.

### Mettre à jour une application déjà installée

1. **Déployer l'API d'abord** (`kaddie-api`) : prod via son déploiement habituel, puis vérifier
   `curl -i https://kaddie-api.cyrilmarchive.com/v1/health` (200) et `/v1/lists` (401 sans jeton).
2. **Construire un APK signé avec la même clé** que la première installation (à refaire après chaque modification du code) :
   ```bash
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # nécessaire dans un terminal
   ./gradlew :app:assembleProdRelease
   ```
3. **Installer par-dessus** (données et connexion conservées). Le plus simple : `scripts/install-prod.sh [numéro-de-série]` construit la release signée, l'installe et la lance (le numéro de série n'est requis que s'il y a plusieurs appareils). À la main :
   ```bash
   adb devices                                   # relever le numéro de série du téléphone
   adb -s <serial> install -r app/build/outputs/apk/prod/release/app-prod-release.apk
   ```
   Avec plusieurs appareils connectés, `-s <serial>` est obligatoire. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` signifie que la
   signature diffère (autre clé, ou version debug installée) : désinstaller d'abord (`adb -s <serial> uninstall com.kaddie.app`) ; la préprod (`com.kaddie.app.preprod`) n'est pas concernée ;
   les listes reviennent via le compte Google.

## Format du dictionnaire

Chaque entrée est `"Nom"` ou `"Nom|alias|alias"`, regroupée par catégorie. Pour que les ajouts soient importés, incrémenter `version` dans `dictionary.json`.
