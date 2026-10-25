# Mode nuit avec TizenTube — preview 3.3

Le mode nuit utilise TizenTube Cobalt deja installe (`io.gh.reisxd.tizentube.cobalt`) pour lire directement les quatre liens choisis. Aucun fichier MP4 ni telechargement du film entier n'est necessaire. L'ouverture directe du premier lien a ete confirmee par le proprietaire du televiseur. La couche de widgets et les commandes de cette version doivent encore etre testees avec la vraie telecommande.

## Mise a jour

Placez `photos-football-preview.apk` a cote de `adb.exe`, ouvrez PowerShell dans ce dossier et remplacez `<tvip>` par l'adresse du televiseur :

```powershell
.\adb.exe connect <tvip>:5555
.\adb.exe -s <tvip>:5555 install -r .\photos-football-preview.apk
.\adb.exe -s <tvip>:5555 shell appops set com.gathin.ambienttv SYSTEM_ALERT_WINDOW allow
.\adb.exe -s <tvip>:5555 shell am start -n com.gathin.ambienttv/.SlideshowActivity
```

Le mode nuit utilise l'autorisation **Afficher par-dessus les autres applications**. Elle peut aussi etre accordee dans la page ouverte depuis le bouton lune. Le service d'accessibilite **Retour vers les photos**, deja utilise par la configuration precedente, doit etre actif pour retirer automatiquement la couche quand vous quittez TizenTube. Si necessaire, l'ecran du mode nuit ouvre les reglages d'accessibilite : activez uniquement ce service, en conservant les autres. Il observe les noms de fenetre et ne lit pas leur contenu.

Seul le compagnon Photos & Football est mis a jour. Les photos et le choix HOME ne sont pas modifies par ces commandes.

## Telecommande

Dans le diaporama, **Haut** selectionne la lune, puis **OK** ouvre le mode nuit. **Gauche/Droite** gardent leur fonction photo precedente/suivante.

Les widgets conservent leur style : meteo en haut a gauche, Wi-Fi en haut a droite, heure et date en bas a droite. Le lecteur TizenTube reste une application distincte sous cette couche.

Dans le mode nuit, **OK** ou une direction affiche les commandes. **Playlist** ouvre les quatre choix; selectionner une video ferme la liste. **Lumiere tamisee / Lumiere normale** modifie uniquement la couche sombre au-dessus de la video. Les commandes se masquent apres cinq secondes.

**Retour** ferme d'abord la liste si elle est ouverte; sinon il revient aux photos. Le bouton **Photos** fait de meme. **Home**, le passage a une autre application, la veille du televiseur ou l'arret du service d'accessibilite retirent la couche. Le niveau sonore est reduit seulement s'il depasse le seuil nocturne, puis restaure si vous ne l'avez pas change avec la telecommande. Un historique local permet de recuperer ce reglage si le processus est interrompu et que vous rouvrez les photos.

## Videos choisies

1. `7_Wbmy79wxQ` — The Art Corner 1
2. `BVdHrTfO2PE` — The Art Corner 2
3. `yTRHheKoVhY` — Bedroom R&B 1
4. `BUeCNBg_qYk` — Bedroom R&B 2

La qualite et le comportement de fin de lecture restent ceux de TizenTube. Cette version ne force pas la 4K et ne pretend pas avoir mesure la resolution de sortie. Aucun compte, historique de visionnage ou flux prive n'est extrait.

## Verification sur le televiseur

Verifiez que la video continue avec les widgets visibles, que Playlist change reellement la video, que la luminosite bascule, que Retour retrouve les photos et que Home retire toute la couche. Les tests automatiques couvrent les etats de sortie et la configuration; ils ne remplacent pas cet essai de lecture sur le televiseur.

En cas de couche qui ne disparaitrait pas, retirez l'autorisation depuis les reglages ou avec :

```powershell
.\adb.exe -s <tvip>:5555 shell appops set com.gathin.ambienttv SYSTEM_ALERT_WINDOW deny
```

Le mode nuit peut ensuite etre reactive avec `allow`. La configuration MP4 native de la preview 3.2 reste dans les sources mais n'est pas requise pour ce mode TizenTube.
