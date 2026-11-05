# CLI trust after the security update

`armour/tv` now requires `~/.config/vibe-tv/remote.pubkey` before pairing,
remote commands, or file uploads. It pins the server public key even though the
TV uses a self-signed certificate. A missing or mismatched pin stops the request.

`armour/install.sh` saves this pin from the local certificate embedded in the APK.
For an existing installation built from this checkout, export that same certificate
locally (using the JDK's `keytool`) and import it:

```sh
keytool -exportcert -rfc -alias armour \
  -keystore armour/app/src/main/res/raw/tls.p12 -storepass armourtls \
  -file armour/tv-certificate.pem
TV_HOST=<tv-ip> armour/tv trust armour/tv-certificate.pem
```

Use the certificate from the build actually installed on your TV, or obtain it
from its installer through a trusted channel. Do not fetch an unverified certificate
from the network to bypass a pin mismatch. Re-import the trusted certificate if you
intentionally rotate the TV key. The existing token and single-TV configuration
continue to work.

Installation/removal now adds/removes only Armour's accessibility component.
Existing screen-reader and button-remapping services are retained.
