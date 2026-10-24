# Armour

A custom Android TV / Google TV home screen, vibecoded with Claude Code. Red and gold, a little Stark,
a little Wayne. It replaces the stock launcher (no root) and adds an AI assistant that can drive any
app with the remote.

Built and tested on a Croma 2K Google TV (Android 14, MediaTek). Other Android TV 9+ sets should work;
the debloat lists in [`../examples`](../examples) cover many brands.

## What it does

- **Home screen:** a curved ring of apps (or a side rail, press Menu to switch), all apps, inputs,
  a living sky that follows the sun, weather and air quality, and a clock night screen.
- **Phone remote:** open `https://<tv ip>:8443` on your phone, pair with the code on the TV. D-pad,
  touch pad, wrist-twist volume, voice, and "throw" a photo or link at the TV.
- **Voice assistant:** speech becomes actions through an LLM on OpenRouter (DeepSeek v4.1 Flash by
  default): open apps, play a film or show in the right streaming app, Spotify, the latest video from a
  YouTube channel, volume, timers ("turn off the TV in 30 minutes"), pause/skip.
- **It uses the remote for you:** for things inside apps ("turn on captions"), an agent looks at the
  screen and presses keys, using Jev 1.14 (TypeSafe) through OpenRouter, about 2-3 s a step.
- **Smart lights:** Philips WiZ bulbs on the same Wi-Fi are found and controlled directly (local UDP,
  no cloud).
- **Scenes:** "Wake up, daddy's home" (Highway to Hell, a welcome dashboard, the lights flash),
  "I am Batman" (red lights, the Batman theme, a Gotham-red UI with a bat signal), "Movie night"
  (lights down, cinema look), "Back to normal".
- **Music changer:** while Spotify plays, the home screen shows a spinning disc, the song, controls and
  a row of vibes to switch to.
- **Present:** send a PDF (slides, change them with the remote's arrows) or a video (plays on a loop).
- **Webcam (optional):** a USB webcam counts people in the room and measures how dark it is, on the TV
  only; no picture is stored or sent.
- **From your computer or a bot:** the `tv` command (`tv "I am Batman"`, `tv key home`,
  `tv present deck.pdf`), so agents that can run commands (for example Grok Bot) can control the TV.

## Set it up

You need a Mac or Linux machine on the same Wi-Fi as the TV, with JDK 17 and the Android command-line
tools (on a Mac: `brew install openjdk@17 android-commandlinetools`).

1. **Turn on ADB over the network on the TV.** Follow [the main README](../README.md) (Settings › About
   › tap Build 7 times, then USB debugging). Then set up the Python ADB client from the repo root:
   `python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`
2. **Optional: remove the bloat** with your TV's list from `examples/` (it disables apps, nothing is
   uninstalled; `tools/restore.sh` undoes it).
3. **Personalise:** `cp armour/personal.properties.example armour/personal.properties` and set your
   name, city and its coordinates (for weather). This file stays out of git.
4. **AI key:** put an [OpenRouter](https://openrouter.ai) key in `~/.config/vibe-tv/openrouter.key`
   (`chmod 600` it). The installer hands it to the TV without printing it. You can also paste it later in
   the phone remote's Settings. A TypeSafe key there is optional.
5. **Install:** `TV_HOST=<tv ip> armour/install.sh`. It builds the app, makes it the home screen and
   creates a fresh HTTPS certificate for the phone remote (your own, never committed).
6. **Phone remote:** open `https://<tv ip>:8443`, accept the self-signed certificate once, and enter
   the pairing code shown on the TV.
7. **The `tv` command (optional):** `ln -s "$PWD/armour/tv" ~/.local/bin/tv`, save the TV's IP with
   `mkdir -p ~/.config/vibe-tv && echo <tv ip> > ~/.config/vibe-tv/host`, then `tv pair` and
   `tv pair <code>`.

Undo everything: `TV_HOST=<tv ip> armour/uninstall.sh`.

## Notes

- The app is sideloaded and signed with the debug key; it is not meant for the Play Store.
- Spotify, YouTube, Netflix, Prime Video and the other apps are launched through their own deep links
  and intents; they need to be installed and signed in.
- Fonts: Exo 2 and Share Tech Mono, both under the SIL Open Font License 1.1 (see
  `app/src/main/assets/fonts/README.md`).
