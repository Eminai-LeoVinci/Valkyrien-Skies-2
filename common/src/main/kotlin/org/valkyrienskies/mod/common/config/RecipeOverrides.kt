package org.valkyrienskies.mod.common.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.valkyrienskies.mod.util.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Config-driven crafting recipe overrides.
 *
 * Reads `config/vs_eureka_recipes.json` (a friendly 9-slot format) and converts each entry into a
 * standard Minecraft crafting-recipe JSON. [org.valkyrienskies.mod.mixin.feature.config_recipes.MixinRecipeManager]
 * feeds those to `RecipeManager.fromJson` at recipe-load time, REPLACING the matching built-in
 * recipe (matched by id). Re-read on every recipe (re)load, so `/reload` picks up edits live.
 *
 * - File absent  -> the bundled defaults are written out, then loaded.
 * - File present -> parsed; malformed entries are skipped (built-in recipe left intact); a wholly
 *   unparseable file logs a warning and leaves ALL built-ins untouched (file not overwritten).
 *
 * Friendly format, one entry per recipe id:
 *   "vs_eureka:engine": {
 *     "type": "shaped" | "shapeless",   // optional, default "shaped"
 *     "slots": [ s1..s9 ],              // REQUIRED, exactly 9; slot 1=top-left, 5=centre, 9=bottom-right
 *     "result": "namespace:item",       // REQUIRED
 *     "count": 1,                        // optional, default 1
 *     "group": "optional"
 *   }
 * A slot is one of:
 *   "namespace:item"  (a bare "item" assumes minecraft:)   e.g. "minecraft:stick" / "stick"
 *   "#namespace:tag"  (any item in the tag)                e.g. "#minecraft:planks"
 *   ["item_a","item_b", ...]  (interchangeable alternatives — any one works)
 *   ""  or  null      (empty slot)
 *
 * To DISABLE a built-in recipe entirely, set its value to the string "remove" (or { "remove": true }).
 * Keys beginning with "_" are ignored (used for inline notes, since JSON has no comments).
 */
object RecipeOverrides {
    private val logger by logger()
    private val CONFIG_FILE: Path = Path.of("config", "vs_eureka_recipes.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private var overrideCache: Map<String, JsonObject> = LinkedHashMap()
    private var removalCache: Set<String> = LinkedHashSet()

    /** Re-read the config from disk (called once per recipe reload by the mixin). */
    @JvmStatic
    fun reload() = load()

    /** id -> standard crafting-recipe JSON (to add/replace). Valid after [reload]. */
    @JvmStatic
    fun getOverrides(): Map<String, JsonObject> = overrideCache

    /** recipe ids to remove entirely. Valid after [reload]. */
    @JvmStatic
    fun getRemovals(): Set<String> = removalCache

    @JvmStatic
    fun logInfo(msg: String) = logger.info(msg)

    @JvmStatic
    fun logError(msg: String, t: Throwable) = logger.error(msg, t)

    private fun load() {
        val overrides = LinkedHashMap<String, JsonObject>()
        val removals = LinkedHashSet<String>()
        try {
            if (!Files.exists(CONFIG_FILE)) {
                CONFIG_FILE.parent?.let { Files.createDirectories(it) }
                Files.writeString(CONFIG_FILE, gson.toJson(defaultConfig()))
                logger.info("Created default recipe config at " + CONFIG_FILE.toAbsolutePath())
            }
            val root = JsonParser.parseString(Files.readString(CONFIG_FILE)).asJsonObject
            for ((id, specEl) in root.entrySet()) {
                if (id.startsWith("_")) continue // inline-note key
                if (specEl.isJsonPrimitive && specEl.asString.equals("remove", ignoreCase = true)) {
                    removals.add(id); continue
                }
                if (!specEl.isJsonObject) continue
                val spec = specEl.asJsonObject
                try {
                    // Inside the try: a malformed "remove" value (object/array/null) throws from
                    // asBoolean and must skip just this entry, not abort the whole file.
                    if (spec.get("remove")?.asBoolean == true) {
                        removals.add(id); continue
                    }
                    overrides[id] = toStandardRecipe(spec)
                } catch (e: Exception) {
                    logger.warn("Skipping malformed recipe override '" + id + "': " + e.message)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "Failed to load recipe config at " + CONFIG_FILE.toAbsolutePath() +
                    " (" + e.message + "); built-in recipes unchanged."
            )
        }
        overrideCache = overrides
        removalCache = removals
    }

    // ---- friendly 9-slot spec -> standard crafting-recipe JSON ----

    private fun toStandardRecipe(spec: JsonObject): JsonObject {
        val type = spec.get("type")?.asString?.lowercase() ?: "shaped"
        val resultId = normalizeId(
            spec.get("result")?.asString ?: throw IllegalArgumentException("missing 'result'")
        )
        val count = spec.get("count")?.asInt ?: 1
        val group = spec.get("group")?.asString

        val slots = spec.getAsJsonArray("slots") ?: throw IllegalArgumentException("missing 'slots'")
        if (slots.size() != 9) {
            throw IllegalArgumentException("'slots' must have exactly 9 entries (got " + slots.size() + ")")
        }

        // 1.21.11 result is an item-stack object keyed by "id" (not "item").
        val result = JsonObject().apply {
            addProperty("id", resultId)
            addProperty("count", count)
        }

        val out = JsonObject()
        if (type == "shapeless") {
            out.addProperty("type", "minecraft:crafting_shapeless")
            val ingredients = JsonArray()
            for (i in 0 until 9) ingredients.add(slotToIngredient(slots.get(i)) ?: continue)
            if (ingredients.isEmpty) throw IllegalArgumentException("shapeless recipe has no ingredients")
            out.add("ingredients", ingredients)
        } else {
            out.addProperty("type", "minecraft:crafting_shaped")
            val key = JsonObject()
            val seen = HashMap<String, String>() // ingredient signature -> assigned char
            var next = 'A'
            val rows = JsonArray()
            for (r in 0 until 3) {
                val sb = StringBuilder(3)
                for (c in 0 until 3) {
                    val ing = slotToIngredient(slots.get(r * 3 + c))
                    if (ing == null) {
                        sb.append(' ')
                    } else {
                        val sig = ing.toString()
                        val ch = seen.getOrPut(sig) {
                            val assigned = next.toString()
                            next++
                            key.add(assigned, ing)
                            assigned
                        }
                        sb.append(ch)
                    }
                }
                rows.add(sb.toString())
            }
            out.add("pattern", rows)
            out.add("key", key)
        }
        out.add("result", result)
        if (group != null) out.addProperty("group", group)
        return out
    }

    /** slot element -> ingredient JSON ({item}/{tag}/array of those) or null if empty. */
    private fun slotToIngredient(el: JsonElement?): JsonElement? {
        if (el == null || el.isJsonNull) return null
        if (el.isJsonArray) {
            val arr = JsonArray()
            for (item in el.asJsonArray) {
                if (item.isJsonPrimitive) stringToIngredient(item.asString)?.let { arr.add(it) }
            }
            return if (arr.isEmpty) null else arr
        }
        if (el.isJsonPrimitive) return stringToIngredient(el.asString)
        return null
    }

    /**
     * 1.21.11 ingredient form is a STRING, not an object: "minecraft:x" for an item, "#minecraft:tag"
     * for a tag. (Alternatives are a json array of these strings, assembled by [slotToIngredient].)
     */
    private fun stringToIngredient(raw: String): JsonElement? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return if (s.startsWith("#")) JsonPrimitive("#" + normalizeId(s.substring(1)))
        else JsonPrimitive(normalizeId(s))
    }

    private fun normalizeId(s: String): String {
        val t = s.trim()
        return if (t.contains(':')) t else "minecraft:$t"
    }

    // ---- bundled defaults: the ORIGINAL Eureka recipes, re-expressed for 1.21.11 ----
    // Eureka ships its recipes under data/vs_eureka/recipes/ (the pre-1.21.2 path) in the old
    // item/tag object format, so none of them load on 1.21.11 and the items would be uncraftable.
    // We regenerate the stock recipes here so a fresh install gets vanilla Eureka behaviour; users
    // can edit this file (or drop in their own overhauls) and /reload to change them.

    private val SHIP_HELM_WOODS = listOf(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "crimson", "warped"
    )
    private val BALLOON_COLORS = listOf(
        "white", "light_gray", "gray", "black", "red", "orange", "yellow", "lime",
        "green", "light_blue", "cyan", "blue", "purple", "magenta", "pink", "brown"
    )

    private fun s(id: String): JsonElement = JsonPrimitive(id)
    private fun none(): JsonElement = JsonPrimitive("")

    private fun shaped(slots: List<JsonElement>, result: String, count: Int, group: String? = null): JsonObject =
        JsonObject().apply {
            addProperty("type", "shaped")
            add("slots", JsonArray().apply { slots.forEach { add(it) } })
            addProperty("result", result)
            addProperty("count", count)
            if (group != null) addProperty("group", group)
        }

    private fun shapeless(ingredients: List<JsonElement>, result: String, count: Int, group: String? = null): JsonObject =
        JsonObject().apply {
            addProperty("type", "shapeless")
            // 'slots' must be exactly 9; pad the unused entries (empties are ignored for shapeless).
            add("slots", JsonArray().apply {
                ingredients.forEach { add(it) }
                repeat(9 - ingredients.size) { add(none()) }
            })
            addProperty("result", result)
            addProperty("count", count)
            if (group != null) addProperty("group", group)
        }

    private fun addBalloonRing(root: JsonObject, id: String, material: JsonElement, count: Int) {
        root.add(
            "vs_eureka:$id",
            shaped(
                listOf(
                    none(), material, none(),
                    material, none(), material,
                    none(), material, none()
                ),
                "vs_eureka:balloon", count, "balloons"
            )
        )
    }

    private fun defaultConfig(): JsonObject {
        val root = JsonObject()
        root.addProperty(
            "_README",
            "vs_eureka recipe overrides (defaults = the original Eureka recipes). 9 slots: 1=top-left .. " +
                "5=centre .. 9=bottom-right. Slot = \"namespace:item\", \"#namespace:tag\", [\"a\",\"b\"] for " +
                "alternatives, or \"\" for empty. type=shaped|shapeless, plus result + count. Set a recipe to " +
                "\"remove\" to disable it. Edit then /reload."
        )

        // Ship helm, one per wood:  / O /   O g O   / _ /   (/=stick, O=wood fence, g=gold, _=wood slab)
        for (w in SHIP_HELM_WOODS) {
            root.add(
                "vs_eureka:${w}_ship_helm",
                shaped(
                    listOf(
                        s("minecraft:stick"), s("minecraft:${w}_fence"), s("minecraft:stick"),
                        s("minecraft:${w}_fence"), s("minecraft:gold_ingot"), s("minecraft:${w}_fence"),
                        s("minecraft:stick"), s("minecraft:${w}_slab"), s("minecraft:stick")
                    ),
                    "vs_eureka:${w}_ship_helm", 1, "ship_helm"
                )
            )
        }

        // Engine:  # # #   i B G   S S S   (#=stone, i=iron ingot, B=blast furnace, G=glass pane, S=smooth stone)
        root.add(
            "vs_eureka:engine",
            shaped(
                listOf(
                    s("minecraft:stone"), s("minecraft:stone"), s("minecraft:stone"),
                    s("minecraft:iron_ingot"), s("minecraft:blast_furnace"), s("minecraft:glass_pane"),
                    s("minecraft:smooth_stone"), s("minecraft:smooth_stone"), s("minecraft:smooth_stone")
                ),
                "vs_eureka:engine", 1
            )
        )

        // Floater = 16:  _ # _   # P #   _ # _   (#=string, P=any planks)
        root.add(
            "vs_eureka:floater",
            shaped(
                listOf(
                    none(), s("minecraft:string"), none(),
                    s("minecraft:string"), s("#minecraft:planks"), s("minecraft:string"),
                    none(), s("minecraft:string"), none()
                ),
                "vs_eureka:floater", 16
            )
        )

        // Anchor:  # i #   _ i _   i I i   (#=lead, i=iron ingot, I=iron block)
        root.add(
            "vs_eureka:anchor",
            shaped(
                listOf(
                    s("minecraft:lead"), s("minecraft:iron_ingot"), s("minecraft:lead"),
                    none(), s("minecraft:iron_ingot"), none(),
                    s("minecraft:iron_ingot"), s("minecraft:iron_block"), s("minecraft:iron_ingot")
                ),
                "vs_eureka:anchor", 1
            )
        )

        // Ballast:  # C #   C _ C   # C #   (#=stone, C=cobblestone)
        root.add(
            "vs_eureka:ballast",
            shaped(
                listOf(
                    s("minecraft:stone"), s("minecraft:cobblestone"), s("minecraft:stone"),
                    s("minecraft:cobblestone"), none(), s("minecraft:cobblestone"),
                    s("minecraft:stone"), s("minecraft:cobblestone"), s("minecraft:stone")
                ),
                "vs_eureka:ballast", 1
            )
        )

        // Base balloons (ring  _ # _   # _ #   _ # _  -> vs_eureka:balloon), one per material.
        addBalloonRing(root, "balloon_membrane", s("minecraft:phantom_membrane"), 32)
        addBalloonRing(root, "balloon_leather", s("minecraft:leather"), 4)
        addBalloonRing(root, "balloon_paper", s("minecraft:paper"), 2)
        addBalloonRing(root, "balloon_wool", s("#minecraft:wool"), 4)

        // Coloured balloons: shapeless  (any balloon) + that dye -> that colour.
        for (c in BALLOON_COLORS) {
            root.add(
                "vs_eureka:${c}_balloon",
                shapeless(
                    listOf(s("#vs_eureka:balloons"), s("minecraft:${c}_dye")),
                    "vs_eureka:${c}_balloon", 1, "colored_balloons"
                )
            )
        }

        return root
    }
}
