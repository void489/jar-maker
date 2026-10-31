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

## Honest limits
- Paper has no API to forbid a mob's physical movement. A path chosen *before* it reached a wall can
  still carry it a short way, and explosions or other velocity can push a mob. The snap-back backstop
  catches those. A hard guarantee would need Minecraft internals, which is fragile across versions.
- Not run on a real server. The distance maths is unit-tested (`ZoneSelfTest.java` passes locally); the
  Paper/MythicMobs integration compiled only by the GitHub build.
- Unverified details: `isFromMythicSpawner()` was reported as always false in older MythicMobs versions;
  `api-version: '26.1'`; `EntitiesLoadEvent`; `MythicSpawner.getName()` equalling the spawner's id.
