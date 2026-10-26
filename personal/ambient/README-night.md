# Mode nuit isole — preview 3.4

Le mode nuit ouvre desormais la copie **TizenTube Nuit** (`com.gathin.tizennight`), distincte de TizenTube habituel (`io.gh.reisxd.tizentube.cobalt`). Les donnees et cookies de votre lecteur habituel ne sont pas copies. Gardez la copie Nuit en mode **Invite / Guest**, sans y connecter votre compte Google. Dans ce cas, les nouvelles lectures de cette copie ne sont pas ajoutees au compte personnel utilise dans TizenTube habituel. Une activite locale ou anonyme du lecteur peut toujours exister. Les entrees deja enregistrees dans votre compte ne sont pas supprimees.

La separation des applications repose sur des identifiants Android differents. L'application Photos & Football ne lit pas le compte ou les cookies de TizenTube Nuit : elle demande de confirmer le choix Invite au premier usage. Si vous connectez un compte dans la copie Nuit plus tard, cette garantie de separation du compte ne s'applique plus.

## Mise a jour

Placez `photos-football-preview.apk` et `tizentube-night.apk` dans platform-tools, a cote de `adb.exe`. Remplacez `<tvip>` par l'adresse du televiseur :

```powershell
.\adb.exe connect <tvip>:5555
.\adb.exe -s <tvip>:5555 install -r .\tizentube-night.apk
.\adb.exe -s <tvip>:5555 install -r .\photos-football-preview.apk
.\adb.exe -s <tvip>:5555 shell appops set com.gathin.ambienttv SYSTEM_ALERT_WINDOW allow
.\adb.exe -s <tvip>:5555 shell am force-stop io.gh.reisxd.tizentube.cobalt
.\adb.exe -s <tvip>:5555 shell am start -n com.gathin.ambienttv/.SlideshowActivity
```

La commande force-stop ferme une seule fois l'ancienne session video de TizenTube habituel. Elle ne supprime pas ses donnees ni son compte. Les nouvelles sessions nocturnes utilisent uniquement TizenTube Nuit; l'application habituelle n'est pas lancee automatiquement en sortie. La copie Nuit conserve son propre processus et peut etre rouverte pour regler sa qualite ou son profil.

## Premier usage Invite

Dans les photos, Haut selectionne la lune, puis OK ouvre le mode nuit. La premiere fois, choisissez **Ouvrir TizenTube Nuit**, puis **Invite / Watch as guest** dans le lecteur. N'y connectez pas votre compte. Revenez a l'ecran Mode nuit et choisissez **J'ai choisi Invite — continuer**. Cette preparation n'est demandee qu'une fois.

Les autorisations de superposition et le service **Retour vers les photos** de la version precedente restent necessaires. Le service observe uniquement les noms de fenetre pour retirer les widgets quand vous quittez la session, sans lire leur contenu.

## Commandes

Gauche/Droite conservent la photo precedente/suivante. En mode nuit, OK ou une direction affiche les commandes. Playlist ouvre les quatre videos et se ferme apres le choix. Le bouton lumiere bascule entre tamisee et normale. Les commandes disparaissent apres cinq secondes.

Retour ferme d'abord la playlist si elle est ouverte; sinon il revient directement a **Home**. Le bouton **Accueil** fait de meme. Home, le passage a une autre application ou la veille retirent les widgets. Le volume nocturne est restaure s'il n'a pas ete change avec la telecommande.

## Lecteur dedie

La copie Nuit est une modification non officielle du lecteur libre TizenTube Cobalt de reisxd. Ses licences et sa notice restent dans l'APK. La mise a jour et l'installation d'APK depuis cette copie sont desactivees pour qu'elle ne remplace pas le lecteur habituel. Source et details de construction : voir les fichiers outils et credits fournis avec cette version.

Aucun film entier n'est telecharge pour le mode nuit. La qualite, les controles propres au lecteur et la fin de lecture restent geres par TizenTube Nuit. La 4K n'est pas mesuree ou garantie par ce build. Le nouveau lecteur et le retour Home doivent etre verifies avec la telecommande sur le televiseur physique.

En cas de besoin, l'autorisation de superposition peut etre retiree :

```powershell
.\adb.exe -s <tvip>:5555 shell appops set com.gathin.ambienttv SYSTEM_ALERT_WINDOW deny
```
