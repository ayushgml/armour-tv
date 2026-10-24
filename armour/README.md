# How Armour is put together

Setup and features are in the [main README](../README.md). This page is the map of the code.

Armour is one Android app (Java, no extra libraries) that is the TV's home screen. A foreground
service keeps the phone remote and the assistant running while other apps are on screen. A small
helper process, started once over the TV's own ADB, lets the app press remote keys inside other apps.

```
phone page / tv command ──HTTPS──▶ RemoteService ──▶ Ai (OpenRouter LLM) ──▶ Commander ──▶ apps, keys, lights, scenes
                                                                    └──▶ Agent (screenshots + Jev) ──▶ KeyServer key presses
```

## Scripts

| File | What it does |
|---|---|
| `install.sh` | Builds the app, creates the phone remote's certificate, installs it, makes it the home screen, enables its services and copies your OpenRouter key to the TV. |
| `uninstall.sh` | Removes Armour and gives the TV its stock home screen back. |
| `tv` | The command-line remote: talk to the assistant, press keys, read the TV's state, pair, present files. |
| `personal.properties.example` | Your name, room, city and coordinates. Copy it to `personal.properties`. |

## The app (`app/src/main/java/com/ayush/armour`)

**Screens**

| File | What it does |
|---|---|
| `MainActivity.java` | The home screen and its layers: the app ring or rail, all apps, the assistant panel, inputs, the night screen, the welcome dashboard and the scene themes. |
| `MusicPanel.java` | The music changer shown on the home screen while Spotify has a song. |
| `PresentActivity.java`, `Present.java` | Full-screen PDF slides and looping videos sent from the phone or the `tv` command. |
| `Ui.java` | The red-and-gold look: colours (and the Batman and cinema palettes), fonts, sizes from the 1280×720 designs. |
| `Views.java` | Drawn views: the living sky, the bat signal, the night clock. |
| `Tile.java` | An app tile with the app's own TV banner. |
| `NightDream.java` | The night screen as the TV's screensaver. |

**Assistant and control**

| File | What it does |
|---|---|
| `Ai.java` | Sends what you said, plus the TV's state, to the LLM on OpenRouter and gets back a reply and a list of actions. |
| `Commander.java` | Carries out those actions: open apps, keys, volume, media, timers, lights, scenes. |
| `Agent.java` | Uses the remote inside apps: reads the screen (view tree or screenshot), decides the next step with Jev 1.14, presses keys, checks again. |
| `Player.java`, `Watch.java` | "Play <film or show>": finds which installed app has it and starts it there. |
| `Spotify.java`, `YouTube.java` | Plays a song on Spotify, or a YouTube search result or a channel's latest video. |
| `NowPlaying.java`, `MediaListener.java` | Reads what is playing (title, artist, cover, position) from Android's media sessions. |
| `Scenes.java` | "Wake up, daddy's home", "I am Batman", "Movie night", "Back to normal". |
| `Lights.java` | Finds Philips WiZ bulbs on the Wi-Fi and sets colour, white and brightness over local UDP. |

**Phone remote and the TV itself**

| File | What it does |
|---|---|
| `RemoteService.java` | The phone remote's API (pairing, keys, voice, uploads) and the service that keeps it all running. |
| `RemoteServer.java` | A small HTTPS server; uploads are streamed to disk. |
| `KeyServer.java`, `Injector.java`, `Adb.java` | The key-press helper: started over the TV's own ADB as the shell user, it presses keys and takes screenshots for the app. |
| `InputKeyService.java` | Makes the remote's Input button open Armour's inputs screen. |
| `Thrown.java` | Photos and links thrown from the phone. |
| `Presence.java` | Optional webcam: counts people and measures room brightness, on the TV only. |
| `Weather.java`, `News.java` | Weather and air quality (Open-Meteo), AI news headlines for the briefing. |
| `Speaker.java` | Reads replies aloud with the TV's speech engine. |
| `TvInputs.java`, `Apps.java` | HDMI and other inputs; the installed apps, their order and launching them. |
| `Net.java`, `Prefs.java` | HTTP helpers; saved settings, keys and paired phones. |

**Assets**

| File | What it does |
|---|---|
| `assets/phone.html` | The whole phone remote page. |
| `assets/fonts/` | Exo 2 and Share Tech Mono (SIL Open Font License, see the README there). |
