# Gerer la playlist Nuit depuis le PC

Apres la mise a jour unique vers **Photos & Football preview 3.5**, le script
`manage-night-videos.ps1` ajoute, retire et affiche les videos YouTube sans
reinstaller l'application. Il fonctionne avec Windows PowerShell 5.1 et `adb.exe`.
Il ne telecharge pas les videos. Les lectures utilisent toujours TizenTube Nuit;
gardez cette copie en mode **Invite / Guest**.

Placez le script dans `platform-tools`, ouvrez PowerShell dans ce dossier et
remplacez `<tvip>` par l'adresse du televiseur. Le debogage reseau doit etre actif;
acceptez l'autorisation ADB sur la TV si elle apparait. Le script se connecte a
l'adresse indiquee.

```powershell
# Ajouter une video avec son titre.
powershell -NoProfile -File .\manage-night-videos.ps1 -Action Add -Url 'https://www.youtube.com/watch?v=aqz-KE-bpKQ' -Title 'Ma video de nuit' -TvHost '<tvip>'

# Afficher la playlist et les liens correspondants.
powershell -NoProfile -File .\manage-night-videos.ps1 -Action List -TvHost '<tvip>'

# Retirer une video par son lien.
powershell -NoProfile -File .\manage-night-videos.ps1 -Action Remove -Url 'https://www.youtube.com/watch?v=aqz-KE-bpKQ' -TvHost '<tvip>'

# Ou retirer une video par son identifiant affiche dans la liste.
powershell -NoProfile -File .\manage-night-videos.ps1 -Action Remove -VideoId 'aqz-KE-bpKQ' -TvHost '<tvip>'
```

Le titre est facultatif. Les liens `youtube.com/watch`, `youtu.be`,
`youtube.com/shorts`, `youtube.com/live` et `youtube.com/embed` sont acceptes.
La playlist contient au maximum 50 videos. Les modifications prennent effet a la
prochaine ouverture du mode nuit : revenez a Home puis ouvrez de nouveau la lune.
Les photos, le compte du lecteur habituel et le choix du launcher Home sont conserves.

Pour eviter de repeter l'adresse dans chaque commande, definissez-la pour la
fenetre PowerShell courante, puis omettez `-TvHost` :

```powershell
$env:TV_HOST = '<tvip>'
.\manage-night-videos.ps1 -Action List
```

Fermez le debogage reseau et revoquez les autorisations ADB apres utilisation,
comme pour les autres commandes de maintenance du televiseur.
