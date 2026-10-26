# Google Agenda sur la TV — preview 3.7

L'agenda affiche les prochains evenements dans Photos & Football et, par defaut,
**trois rappels : debut, milieu et fin** de chaque evenement avec une heure.
Chaque rappel reste environ **10 secondes au-dessus des autres applications**,
lorsque la TV autorise les superpositions, avec un bref son discret. Il lit un
seul agenda Google via son adresse secrete iCal. Les photos, Home et le mode Nuit
restent disponibles comme auparavant.

Le telephone et la TV peuvent etre sur des reseaux differents : cela fonctionne
aussi hors de la maison si l'iPhone et la TV ont chacun Internet. La TV interroge
le flux environ toutes les **60 secondes** quand le service est actif. Google peut
mettre son flux en cache : la mise a jour n'est pas instantanee et aucun delai
maximum n'est garanti. La TV doit etre allumee; le service ne la reveille pas.

## Installation unique

Il faut Windows 10, **Windows PowerShell 5.1**, `adb.exe` et une TV Android TV ou
Google TV. Placez [configure-agenda.ps1](configure-agenda.ps1) et le nouvel APK
`photos-football-preview.apk` de version **3.7 ou plus recente** dans le dossier
`platform-tools`, pres de `adb.exe`. Le script trouve aussi `adb.exe` dans le
dossier courant ou dans le PATH. Cette mise a jour de l'application n'est
necessaire qu'une fois; configurer l'agenda ne reinstalle ensuite rien.

Ouvrez PowerShell dans ce dossier. Remplacez `<tvip>` par l'adresse de la TV,
activez son debogage reseau et acceptez son autorisation ADB :

```powershell
.\adb.exe connect <tvip>:5555
.\adb.exe -s <tvip>:5555 install -r .\photos-football-preview.apk
```

Ne desinstallez pas l'application : `install -r` conserve ses donnees, dont les
photos et la playlist Nuit. Cette procedure ne change pas le launcher Home ni
le compte du lecteur video.

## Copier l'adresse secrete sur le PC

Dans un navigateur sur ordinateur, ouvrez [Google Agenda](https://calendar.google.com/).
Dans **Parametres**, selectionnez l'agenda souhaite sous **Parametres de mes
agendas**, puis **Integrer l'agenda**. Copiez **Adresse secrete au format iCal**.
N'utilisez pas l'adresse publique ou le lien de partage. Certains comptes
professionnels ou scolaires masquent cette adresse; leur administrateur doit
alors autoriser ce mode d'acces.

Cette adresse donne acces en lecture aux evenements de cet agenda. Gardez-la
privee : ne l'envoyez pas a un assistant, ne la mettez pas dans une capture, un
message ou une commande PowerShell. Le script accepte uniquement les liens HTTPS
`calendar.google.com` ou `www.google.com` au format
`/calendar/ical/<identifiant>/private-<jeton>/basic.ics` ou `full.ics`, sans
parametre, fragment, identifiants de connexion ou port personnalise.

## Une commande, puis coller dans la saisie masquee

```powershell
powershell -NoProfile -File .\configure-agenda.ps1 -TvHost '<tvip>'
```

Au message **Adresse secrete Google Agenda au format iCal (saisie masquee)**,
collez le lien, puis appuyez sur Entree. Les caracteres restent masques. Aucun
lien secret n'est necessaire dans la commande, l'historique ou cette conversation.
Si la strategie PowerShell bloque ce fichier, la commande suivante ne change la
strategie que pour ce processus :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\configure-agenda.ps1 -TvHost '<tvip>'
```

Le script se connecte a la TV, enregistre l'adresse sans l'afficher et ouvre les
reglages Agenda sur la TV. Avec la telecommande, autorisez **l'affichage au-dessus
des autres applications** si demande, puis activez **Activer l’agenda et les rappels TV**.
Cette action lance le service d'agenda et sa notification Android persistante.
Lors de la premiere configuration, l'enregistrement de l'adresse seul laisse le
service desactive jusqu'a cette activation sur la TV.

L'adresse passe brièvement en memoire du PC puis en Base64 dans les arguments du
processus ADB local. **Base64 n'est pas un chiffrement** : un outil local capable
d'inspecter ces arguments peut lire ce secret pendant l'envoi. Le script ne
l'ecrit pas dans un fichier temporaire, n'affiche ni cette valeur ni les sorties
ADB detaillees et ne cree aucun journal. Evitez l'enregistrement ou le tracage de
la session pendant la configuration, puis videz le presse-papiers si necessaire.
La copie necessaire au fonctionnement reste dans le stockage prive de
l'application sur la TV. En cas de divulgation, reinitialisez l'adresse secrete
dans Google Agenda, puis relancez la configuration avec la nouvelle adresse.

## Evenements et rappels depuis l'iPhone

Dans l'application **Google Agenda** de l'iPhone, creez un **Evenement**, choisissez
le meme agenda Google que celui configure sur la TV et donnez-lui une date et une
heure. Une note destinee a la TV doit etre dans la **description de cet evenement**.
Les rappels de l'iPhone restent regles separement dans Google Agenda et les
autorisations de notifications d'iOS; les reglages TV ne les changent pas.

Sur la TV, **Trois rappels : debut, milieu et fin** est active par defaut. Pour un
evenement de **16:40 a 16:50**, les trois horaires sont **16:40, 16:45 et 16:50**,
avec une alerte de 10 secondes a chaque fois. Ces horaires remplacent les rappels
du flux pour les evenements avec une heure. Si plusieurs instants coincident,
notamment pour une duree nulle ou tres courte, ils sont regroupes en une seule
alerte.

**Son discret des rappels** est aussi active par defaut : un son faible d'environ
150 ms accompagne l'alerte. Il respecte le volume des notifications, le mode
silencieux et **Ne pas deranger**, sans changer le volume de la video ni demander
le focus audio.

Ces deux interrupteurs sont en haut des reglages **Agenda Google** sur la TV.
Leur choix est enregistre immediatement et s'applique au prochain controle du
service actif. Ils ne demarrent pas le service si l'agenda est desactive. Une mise
a jour conserve un choix desactive deja enregistre.

Desactivez **Trois rappels : debut, milieu et fin** pour le **mode classique** :
les rappels `DISPLAY` presents dans le flux iCal sont utilises. Si le flux n'en
contient pas, un rappel unique arrive par defaut **10 minutes avant** l'evenement.
Le reglage TV **Mode classique : delai de secours** choisit ce delai. Les
notifications ajoutees dans l'application Google Agenda ne sont pas toujours
exportees dans ce flux. Pour choisir le delai de secours lors de la configuration :

```powershell
powershell -NoProfile -File .\configure-agenda.ps1 -TvHost '<tvip>' -DefaultMinutes 15
```

Les valeurs possibles sont `0`, `5`, `10`, `15`, `30` et `60` minutes; `0` signifie
a l'heure de l'evenement. Reconfigurer demande de nouveau l'adresse masquee et
ouvre les reglages sur la TV.

Un evenement sur toute la journee conserve le mode classique, avec **09:00, heure
du televiseur**, comme reference pour ses rappels du flux ou le delai de secours.
Il ne declenche pas les trois rappels a minuit. Les rappels deja passes a la
premiere lecture, pendant l'arret de la TV ou avant la reprise du service ne sont
pas rejoues.

Ce flux est **en lecture seule** : la TV ne cree, ne modifie et ne supprime aucun
evenement Google. Les **Taches Google**, Google Keep, les Rappels Apple et les notes
iCloud ne sont pas importes automatiquement. Dans Google Agenda, choisir
**Tache** au lieu d'**Evenement** ne cree donc pas un rappel TV.

Pour un essai, creez un evenement a une heure future dans l'agenda choisi, laissez
le temps au flux de se mettre a jour, puis ouvrez une autre application sur la TV
et attendez le rappel. Verifiez aussi la carte agenda dans les photos. Certaines
applications et certaines TV peuvent bloquer les superpositions; la compatibilite
reelle, le redemarrage et les alertes au-dessus du lecteur restent a verifier avec
la telecommande sur le televiseur physique. Une alerte TV ne remplace pas un rappel
fiable sur le telephone.

## Gerer la configuration sans nouveau lien

Les commandes suivantes ne demandent pas l'adresse secrete et ne la renvoient pas :

```powershell
# Voir si l'agenda est configure et active, avec le delai par defaut.
powershell -NoProfile -File .\configure-agenda.ps1 -Action Status -TvHost '<tvip>'

# Demander une actualisation; le cache Google peut encore retarder le resultat.
powershell -NoProfile -File .\configure-agenda.ps1 -Action Sync -TvHost '<tvip>'

# Arreter le service et les rappels, en gardant l'adresse pour plus tard.
powershell -NoProfile -File .\configure-agenda.ps1 -Action Disable -TvHost '<tvip>'

# Reprendre avec l'adresse conservee et ouvrir les reglages sur la TV.
powershell -NoProfile -File .\configure-agenda.ps1 -Action Enable -TvHost '<tvip>'

# Arreter et retirer l'adresse et les donnees agenda locales de la TV.
powershell -NoProfile -File .\configure-agenda.ps1 -Action Disconnect -TvHost '<tvip>'
```

`Disconnect` ne supprime pas les evenements Google et ne revoque pas l'adresse
chez Google. Pour la revoquer, utilisez **Reinitialiser** a cote de l'adresse
secrete dans les parametres Google Agenda.

Apres la maintenance, desactivez le debogage reseau et revoquez les autorisations
ADB sur la TV. La synchronisation Internet de l'agenda continue sans ADB tant que
le service est actif.
