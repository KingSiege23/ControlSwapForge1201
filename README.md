# Control Swap Forge 1.20.1

Forge 1.20.1 implementation of the Control Swap concept.

## Build the JAR automatically with GitHub

1. Create a new GitHub repository.
2. Upload all files in this project to the repository root.
3. Open the **Actions** tab.
4. Select **Build Control Swap Forge 1.20.1**.
5. Click **Run workflow**.
6. When the build finishes, open the workflow run and download the artifact named **ControlSwapForge-1.20.1**.
7. Put the downloaded `.jar` into your server/client `mods` folder.

The workflow uses Java 17 and ForgeGradle and builds the JAR with `gradle build`.

## Commands

All commands require OP level 2.

- `/controlswap create <team>`
- `/controlswap addplayer <team> <player>`
- `/controlswap removeplayer <player>`
- `/controlswap period <team> <ticks>`
- `/controlswap start <team>`
- `/controlswap stop <team>`
- `/controlswap bench <player> <true|false>`
- `/controlswap list`
- `/controlswap swap <team>`

600 ticks = 30 seconds. 200 ticks = 10 seconds. 1200 ticks = 60 seconds.
