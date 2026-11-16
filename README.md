# Throwing Discs (Fabric, Minecraft Java 26.3)

Port of the Bedrock add-on *Throwing Discs v1.21* (by Ace) to a Fabric mod for Java Edition 26.3.

Copper, iron, golden, diamond and netherite discs. Right-click to throw: the disc flies straight, ricochets off
hard blocks, pierces every mob in its path, then curves back to you like a boomerang. Soft blocks (wool, leaves,
hay, moss, ...) kill its momentum, slime blocks fling it further, decorated pots get smashed.
Sharpness / Smite / Bane / Knockback / Fire Aspect / Unbreaking all work.

## Build with GitHub Actions (no local Java needed)

Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml` builds it with JDK 25 and
uploads the jar: repo > Actions > latest run > Artifacts > `throwing-discs-jar`.

## Build locally

Needs **JDK 25** (Minecraft 26.x requirement).

    gradle wrapper --gradle-version 9.6.0   # once; the wrapper script/jar couldn't be generated offline
    ./gradlew build          # jar ends up in build/libs/throwing-discs-1.0.0.jar
    ./gradlew runClient      # test in a dev client

Versions (gradle.properties): Minecraft 26.3, Fabric Loader 0.19.5, Loom 1.17, Fabric API 0.165.0+26.3, Gradle 9.6.
Players need Fabric Loader + Fabric API for 26.3.

## What was ported

| Bedrock | Java |
|---|---|
| 5 disc items (durability, enchantability, repair ingots) | `ModItems`, `DiscTier`, `ThrowingDiscItem` |
| `main.js` flight / bounce / return / catch / hit logic | `ThrowingDiscEntity` (same constants and probe-ray collision) |
| Recipes (crafting, smelting, netherite smithing) | `data/tdisc/recipe/` (smelting also gets a blasting variant) |
| Trial chamber `reward_rare` / `reward_ominous_rare` overrides | extra loot pool added via Fabric's `LootTableEvents` (vanilla rewards are kept) |
| Enchantments on discs | `data/minecraft/tags/item/enchantable/*` |
| Textures, names | `assets/tdisc/` |

## What was left out on purpose

- `offhand.js`, `foods.js` and the 600+ "off-hand block" attachables: those were a Bedrock workaround for
  not having a proper off-hand. Java already has one, so a disc in either hand is thrown with right-click.
- Custom Bedrock sounds: vanilla trident / wool / shield sounds are used directly.
- The 3D spinning disc model: the thrown disc is drawn with its item model (billboarded sprite).

## Not compiled yet

This was written without access to the Minecraft 26.3 jar, so it has not been compiled or run. If the build
complains, the likeliest spots are mapped-name differences in 26.x:
`net.minecraft.world.entity.projectile.ItemSupplier` and `ThrownItemRenderer` (package may have moved),
`Entity#is(TagKey)` used for the undead/arthropod check, `Level#playSound` / `SoundEvents` holder types,
`Inventory#getSelectedSlot`, and the Fabric `LootTableEvents.MODIFY` lambda signature.
