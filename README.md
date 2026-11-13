# Stratos

Named Vapora until 2026-11-12 (0.2.2 and older); the earlier Stratos (0.10.0 and older) is the sky mod, now Atmosphera.

Weather for the Atmosphera suite (Forge 1.20.1). Version 0.3.0.

- **Kinds of rain:** drizzle, light rain, rain, heavy rain and storms, decided on the server for each spell, with a
  forecast. In singleplayer the world's own server decides.
- **One weather for the family:** Atmosphera's clouds, Gale's wind, Torrente's rain rings and Stratos's rain follow the
  same state, through AtmospheraLib's Weather service.
- **Rain on the GPU:** fine streaks drawn in one call, as heavy as the weather is, slanted by the wind, stopped by
  roofs. Snow stays vanilla's.
- **Gameplay is vanilla's:** crops, fire and mob spawning follow vanilla's rain.

Needs AtmospheraLib 0.9.1 or newer. Optional on both sides: a client without Stratos joins a server with it, and the
reverse. On a server without Stratos, clients use vanilla's rain levels (no forecast).

## Settings
- `config/Atmosphera/stratos-client.toml` (Video Settings, page "Stratos"): the rain renderer, its density and opacity.
- `config/Atmosphera/stratos-server.toml`: the weather model, the share of each kind of rain, how patchy rain is.
- `/stratos status | set <kind> [intensity] | auto | reload` (operators).

## Licence
All Rights Reserved (see `LICENSE`): the source is published to be read; unmodified jars may be included in modpacks
with credit.
