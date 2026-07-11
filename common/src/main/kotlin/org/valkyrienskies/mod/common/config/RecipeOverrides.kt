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
 * splices those into the datapack id->JSON map before `RecipeManager.apply` parses it, REPLACING the
 * matching built-in recipe (matched by id). Re-read on every recipe (re)load, so `/reload` picks up
 * edits live.
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

        // 1.20.5+/1.21.1 result is an item-stack object keyed by "id" (not "item").
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
     * 1.21.1 ingredient form is an OBJECT (unlike 1.21.2+'s bare string): {"item": "minecraft:x"}
     * for an item, {"tag": "minecraft:t"} for a tag. (Alternatives are a json array of these
     * objects, assembled by [slotToIngredient].)
     */
    private fun stringToIngredient(raw: String): JsonElement? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return JsonObject().apply {
            if (s.startsWith("#")) addProperty("tag", normalizeId(s.substring(1)))
            else addProperty("item", normalizeId(s))
        }
    }

    private fun normalizeId(s: String): String {
        val t = s.trim()
        return if (t.contains(':')) t else "minecraft:$t"
    }

    // ---- bundled defaults: the same recipe set the 1.21.11 build ships ----
    // Eureka ships its recipes under data/vs_eureka/recipes/ (the pre-1.21 path) in the old
    // item/tag object format, so none of them load on 1.21+ and the items would be uncraftable.
    // We regenerate the recipes here so a fresh install gets craftable Eureka items; users can
    // edit this file (or drop in their own overhauls) and /reload to change them.

    private val SHIP_HELM_WOODS = listOf(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "crimson", "warped"
    )
    private val BALLOON_COLORS = listOf(
        "white", "light_gray", "gray", "black", "red", "orange", "yellow", "lime",
        "green", "light_blue", "cyan", "blue", "purple", "magenta", "pink", "brown"
    )

    private fun s(id: String): JsonElement = JsonPrimitive(id)
    private fun none(): JsonElement = JsonPrimitive("")

    /** A slot accepting any one of several items (interchangeable alternatives). */
    private fun anyOf(vararg ids: String): JsonElement =
        JsonArray().apply { ids.forEach { add(JsonPrimitive(it)) } }

    /** Any glass pane, coloured or not (the engine doesn't care which). */
    private fun anyGlassPane(): JsonElement = anyOf(
        "minecraft:glass_pane",
        "minecraft:white_stained_glass_pane", "minecraft:orange_stained_glass_pane",
        "minecraft:magenta_stained_glass_pane", "minecraft:light_blue_stained_glass_pane",
        "minecraft:yellow_stained_glass_pane", "minecraft:lime_stained_glass_pane",
        "minecraft:pink_stained_glass_pane", "minecraft:gray_stained_glass_pane",
        "minecraft:light_gray_stained_glass_pane", "minecraft:cyan_stained_glass_pane",
        "minecraft:purple_stained_glass_pane", "minecraft:blue_stained_glass_pane",
        "minecraft:brown_stained_glass_pane", "minecraft:green_stained_glass_pane",
        "minecraft:red_stained_glass_pane", "minecraft:black_stained_glass_pane"
    )

    // Single slot-writer for both recipe types. Empty slots MUST serialize as the empty string "";
    // a shaped/shapeless split here previously let padding slots serialize as a JSON boolean, which
    // made every shapeless recipe fail to build ("Unknown registry key ... minecraft:false").
    private fun recipeJson(type: String, slots: List<JsonElement>, result: String, count: Int, group: String?): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            add("slots", JsonArray().apply { slots.forEach { add(it) } })
            addProperty("result", result)
            addProperty("count", count)
            if (group != null) addProperty("group", group)
        }

    private fun shaped(slots: List<JsonElement>, result: String, count: Int, group: String? = null): JsonObject =
        recipeJson("shaped", slots, result, count, group)

    private fun shapeless(ingredients: List<JsonElement>, result: String, count: Int, group: String? = null): JsonObject {
        // 'slots' must be exactly 9; pad the unused entries with "" (ignored for shapeless).
        val slots = ingredients.toMutableList()
        while (slots.size < 9) slots.add(none())
        return recipeJson("shapeless", slots, result, count, group)
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

        // Ship helm, one per wood:  B F B   F h F   S L S
        // (B=iron bars, F=wood fence [picks the wood type — all three must match], h=heart of the sea,
        //  S=wood slab [matches the wood type], L=lodestone)
        for (w in SHIP_HELM_WOODS) {
            root.add(
                "vs_eureka:${w}_ship_helm",
                shaped(
                    listOf(
                        s("minecraft:iron_bars"), s("minecraft:${w}_fence"), s("minecraft:iron_bars"),
                        s("minecraft:${w}_fence"), s("minecraft:heart_of_the_sea"), s("minecraft:${w}_fence"),
                        s("minecraft:${w}_slab"), s("minecraft:lodestone"), s("minecraft:${w}_slab")
                    ),
                    "vs_eureka:${w}_ship_helm", 1, "ship_helm"
                )
            )
        }

        // Engine:  S S S   F R G   I I T
        // (S=smooth stone, F=blast furnace, R=lightning rod, G=any glass pane, I=iron block, T=iron trapdoor)
        // 1.21.1 has no #minecraft:lightning_rods tag (single un-oxidizable rod), so the plain item is used.
        root.add(
            "vs_eureka:engine",
            shaped(
                listOf(
                    s("minecraft:smooth_stone"), s("minecraft:smooth_stone"), s("minecraft:smooth_stone"),
                    s("minecraft:blast_furnace"), s("minecraft:lightning_rod"), anyGlassPane(),
                    s("minecraft:iron_block"), s("minecraft:iron_block"), s("minecraft:iron_trapdoor")
                ),
                "vs_eureka:engine", 1
            )
        )

        // Floater = 8:  W B W   B _ B   W B W   (W=any wooden slab, B=barrel)
        root.add(
            "vs_eureka:floater",
            shaped(
                listOf(
                    s("#minecraft:wooden_slabs"), s("minecraft:barrel"), s("#minecraft:wooden_slabs"),
                    s("minecraft:barrel"), none(), s("minecraft:barrel"),
                    s("#minecraft:wooden_slabs"), s("minecraft:barrel"), s("#minecraft:wooden_slabs")
                ),
                "vs_eureka:floater", 8
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

        // Balloon = 8:  L M L   M N M   L M L
        // (L=leather, M=phantom membrane, N=nether star) -- the single, Nether-Star-gated balloon recipe.
        root.add(
            "vs_eureka:balloon",
            shaped(
                listOf(
                    s("minecraft:leather"), s("minecraft:phantom_membrane"), s("minecraft:leather"),
                    s("minecraft:phantom_membrane"), s("minecraft:nether_star"), s("minecraft:phantom_membrane"),
                    s("minecraft:leather"), s("minecraft:phantom_membrane"), s("minecraft:leather")
                ),
                "vs_eureka:balloon", 8, "balloons"
            )
        )

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
