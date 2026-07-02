# CorePlugin

Paper plugin for Minecraft 1.21.11 that makes each player protect a placed core.

## Gameplay

- New players receive one player core and one Corebreaker.
- A player core is a special beacon block. Place it to anchor your normal respawns.
- Normal deaths respawn you near your placed core, if there is a safe space nearby.
- If the core area is blocked or unsafe, vanilla bed/world spawn behavior is used.
- Player cores can only be destroyed with a Corebreaker.
- Breaking a core gives the breaker mining fatigue.
- Destroying another player's core spends one Corebreaker charge, kills that player, and drops their death items at the core.
- After a core death, the victim respawns at their bed if they have one and receives a new core item.
- `/core` teleports you to your core after 3 seconds.
- `/selfdestruct` removes your placed core and returns it as an item, with a configurable cooldown.
- `/kills` shows your unique kill queue in the order charges will be consumed.
- Holding your unplaced core drains a 300 second core hold timer. If it reaches 0, you die.
- Placing your core refills the hold timer by 1 second per second, up to the 300 second maximum.

## Corebreakers and Kills

Corebreaker charges are based on your unique kill queue.

- Killing a player normally adds that player's name to the back of your `/kills` queue.
- Re-killing a player already in your queue does not add another charge.
- Breaking a core removes the oldest name from the front of your queue.
- Corebreaker items only show the current charge count.
- Corebreakers cannot be dropped, traded, picked up, or placed into containers.
- If your inventory is full and the plugin must restore your Corebreaker, it puts it in hotbar slot 9 and drops the displaced normal item.

## Abuse Protection

The default config starts conservative:

- Offline core protection starts 10 minutes after the player leaves.
- If a player stays offline for 30 days, their core is automatically destroyed and the core item drops at the core location.
- Repeat kills against the same queued player do not add charges.
- Players cannot manually mine their own core; they must use `/selfdestruct`.
- `/selfdestruct` has a 5 minute cooldown by default.
- Core items cannot be picked up by other players, dropped manually, or placed into containers.

The core hold timer is intentionally simple: carrying your unplaced core spends your 300 seconds, and having it placed recharges that time at 1 second per second. This lets players move bases, but it makes hiding the core in inventory a temporary panic option instead of a permanent strategy.

## Stack

- Java 21
- Maven
- Paper API 1.21.11

## Build

```powershell
mvn clean package
```

The jar is created in `target/` and copied to `C:\Users\Admin\Desktop\test server\plugins` during packaging, matching the Fireworks Elytra workflow.

## GitHub Actions

The workflow in `.github/workflows/build.yml` builds the plugin with Java 21 on pushes, pull requests, and manual runs.
