# Stratos

Named Vapora until 2026-11-12 (0.2.2 and older); the earlier Stratos (0.10.0 and older) is the sky mod, now Atmosphera.

Weather for the Atmosphera suite (Forge 1.20.1). Version 0.4.0.

- **Kinds of rain:** drizzle, light rain, rain, heavy rain and storms, decided on the server for each spell, with a
  forecast. In singleplayer the world's own server decides.
- **One weather for the family:** Atmosphera's clouds, Gale's wind, Torrente's rain rings and Stratos's rain follow the
  same state, through AtmospheraLib's Weather service.
- **Each kind looks its own:** drizzle is short, thin and faint; heavy rain and storms have more, longer, darker
  streaks. Timing stays vanilla's.
- **Sandstorms:** a rain spell over a dry sandy biome (deserts, badlands) blows sand streaks along the wind, and with
  Atmosphera 0.12.0 the fog closes in tan.
- **Rain on the GPU:** fine streaks drawn in one call, as heavy as the weather is, slanted by the wind, stopped by
  roofs. Snow stays vanilla's.
- **Gameplay is vanilla's:** crops, fire and mob spawning follow vanilla's rain. The one exception is the optional sandstorm effects (below).

Needs AtmospheraLib 0.10.1 or newer. Optional on both sides: a client without Stratos joins a server with it, and the
reverse. On a server without Stratos, clients use vanilla's rain levels (no forecast).

## Settings
- `config/Atmosphera/stratos-client.toml` (Video Settings, page "Stratos"): the rain renderer, its density and opacity. Settings are grouped in dropdowns (closed by default, and remembered); without Xenon or Embeddium, the library's settings screen shows the same groups.
- `config/Atmosphera/stratos-server.toml`: the weather model, the share of each kind of rain, how patchy rain is, and `weather.sandstorm_effects` (on by default): in a sandstorm, walking into the wind is up to 12% slower and hostile mobs within 64 blocks lose up to 40% of their follow range. Set it to false for visual-only sandstorms.
- `/stratos status | set <kind> [intensity] | auto | reload` (operators).

## Licence
All Rights Reserved (see `LICENSE`): the source is published to be read; unmodified jars may be included in modpacks
with credit.
