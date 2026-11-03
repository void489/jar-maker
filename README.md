# MobZone

Keeps MythicMobs **spawner** mobs inside a cuboid zone. Players, natural mobs, command-spawned
MythicMobs and mobs from spawners you did not assign are never touched.

## Build (no local tools needed)
1. Create a free GitHub repository and upload everything in this folder (keep `.github/`).
2. Open the **Actions** tab, pick **Build MobZone**, click **Run workflow** (it also runs on every push).
3. When it finishes, open the run and download the **MobZone-jar** artifact. Unzip it to get `MobZone-1.0.0.jar`.

The workflow builds with Java 25 and tries two Paper API version formats, because the format for
26.1.x could not be confirmed. If both fail, open the failed step and send me the red lines.

## Install
1. Put `MobZone-1.0.0.jar` in `plugins/` and restart the server. MythicMobs must be installed.
2. Stand at one corner of a zone: `/mobzone pos1`. Stand at the opposite corner: `/mobzone pos2`.
3. `/mobzone create <name>` (optionally `/mobzone create <name> <minY> <maxY>`).
   Without Y values the top is raised by `y-extend-up` (default 6) above your highest selected Y.
4. Assign spawners: `/mobzone addspawner <zone> <spawnername>`. The name is the spawner's name in MythicMobs.
   `zones-spawners.txt` has all of them for your 10 zones, ready to paste into the console.
5. Mobs that are already alive are not affected. Let them die or run `mm killall`, so they respawn tagged.

Commands: `pos1`, `pos2`, `create`, `delete`, `list`, `info`, `addspawner`, `removespawner`, `reload`.
Permission: `mobzone.admin` (op by default). Zones are stored in `config.yml` and can be edited by hand.

## How it works
- On `MythicMobSpawnEvent`, if the mob came from a spawner assigned to a zone it gets a persistent
  tag and is added to a tracking map. Only those mobs are ever checked.
- `EntityPathfindEvent`: a move toward a point or entity outside the zone is cancelled before it starts
  (covers chasing and strolling) - the mob simply picks something else, so there is no edge jitter.
- `EntityTargetEvent`: a mob never targets something outside its zone; a target that leaves is dropped.
- `EntityKnockbackEvent`: knockback toward a wall is faded out near that wall, so hits can't push a mob out.
- Every `check-interval-ticks` (default 4), tracked mobs only: a path that would leave the zone is stopped
  when the mob is near a wall; a mob that is somehow outside is moved back to the nearest inside point
  (rare backstop; turn off with `snap-on-breach: false`).
- Dead, despawned or unloaded mobs are removed from tracking automatically. Tagged mobs are re-tracked on chunk load.

## Timed boss events (v1.1.0)
Configured under `boss:` in `config.yml`. Every `interval-minutes` (60) one boss from a random zone spawns, as long
as at least `min-players` (1) player is in the prison world. Chat countdown at 5 min / 1 min / 10 s (zone name only).
Only one boss is ever alive. An unkilled boss leaves after `despawn-minutes` (30). Cash (`eco give`) is split by damage
dealt; players under `min-damage-share` (5%) of the boss's health get nothing. The boss is tracked by MobZone like a
spawner mob, so it cannot leave its zone. Remove the old `boss_*` spawner files: the plugin spawns bosses itself.

Admin: `/mobzone boss spawn [zone|random]`, `/mobzone boss despawn`, `/mobzone boss status`, `/mobzone boss reset`.

Unverified: `MythicMob#spawn(location, level)` with level `1`, `getMythicMob(name).orElse(null)`.
Boss minions come from the boss's own summon skill, so they are not zone-tracked.

## Honest limits
- Paper has no API to forbid a mob's physical movement. A path chosen *before* it reached a wall can
  still carry it a short way, and explosions or other velocity can push a mob. The snap-back backstop
  catches those. A hard guarantee would need Minecraft internals, which is fragile across versions.
- Not run on a real server. The distance maths is unit-tested (`ZoneSelfTest.java` and `BossSelfTest.java` pass locally); the
  Paper/MythicMobs integration compiled only by the GitHub build.
- Unverified details: `isFromMythicSpawner()` was reported as always false in older MythicMobs versions;
  `api-version: '26.1'`; `EntitiesLoadEvent`; `MythicSpawner.getName()` equalling the spawner's id.

## Map changes (2.1.0)
After pasting a new map and fixing each zone's pos1/pos2 (`/mobzone create` or the zones: block):
- `/mobzone update all preview`  shows where every spawner and boss would go (nothing is changed).
- `/mobzone update all`          moves every spawner file + boss spot, then reloads MythicMobs. Only ground-floor spots with real air above are used (never tables, catwalks or balconies).
- `/mobzone update <zone>`       one zone only. If you stand inside it, your position is the starting point.
- `/mobzone set spawner <name>` / `/mobzone set boss <zone>`   move one thing to where you stand.

- Add `y=104` (your floor height, the Y you see in F3 while standing on the floor) so spawners can never end up underground: `/mobzone update all y=104`.
- Spawners are moved through MythicMobs itself (not by editing files), so MythicMobs can't overwrite them.

## Prison rules (2.2.0)
- No hunger loss in the prison world. Chicken jockeys are removed. The 'Prison Blade' can't be dropped, moved or lost on death.
- Defaults need no config. Optional: `no-hunger: {enabled: true, worlds: [prison]}`, `no-chicken-jockeys: {enabled: true}`, `locked-items: {names: ["Prison Blade"]}`. Admins with `mobzone.bypasslock` can move the sword.

## Gear repair (2.3.0)
`/mobzone restore <player> [sword|armor|all]` replaces the sword and/or armor with fresh ones enchanted to the player's saved levels (LuckPerms meta). Upgrade levels are never reset. Ignores kit limits.
