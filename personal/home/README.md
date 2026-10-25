# Accueil Personnel preview

Isolated launcher fork, application ID `com.gathin.personalhome`. It keeps Java namespace `com.gathin.tvhome` for the existing module-card contract. The original launcher project is unchanged.

See [README-personal.md](README-personal.md) for installation, preview, activation and restoration. Use the supplied preview scripts rather than the original launcher installer.

Build with JDK17 and Android SDK34 from this folder:

```sh
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

The companion `../ambient` must be signed with the same private key to access its signature-protected recommendation/artwork provider. No Internet permission is added to this launcher. Shared account content is limited to rows the installed apps make searchable in Android TV; Netflix public Top10 is labelled separately and opens Netflix itself.
