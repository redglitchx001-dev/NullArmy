/*
 * NullArmy - physically simulated, player-like Null NPCs for Paper.
 * Copyright (c) RedGlitchX. All rights reserved.
 *
 * Multi-module topology (see IMPLEMENTATION_PLAN.md section 4.2):
 *   core     - version-independent, NMS-free, Bukkit-free. Pure logic. Unit testable.
 *   nms/api  - the VersionAdapter SPI. Interfaces only.
 *   nms/vX   - one module per supported server version (paperweight-userdev).
 *   plugin   - Bukkit bootstrap, commands, configuration, persistence.
 */
rootProject.name = "NullArmy"

include(":core")
include(":nms:api")
include(":nms:v1_21_11")
include(":plugin")
