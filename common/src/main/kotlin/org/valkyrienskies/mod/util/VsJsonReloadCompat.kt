package org.valkyrienskies.mod.util

import com.google.gson.JsonElement
import com.mojang.serialization.Codec
import com.mojang.serialization.Dynamic
import com.mojang.serialization.JsonOps
import net.minecraft.resources.FileToIdConverter

// 1.21.11: SimpleJsonResourceReloadListener became generic <T> with a codec-based constructor;
// the old (Gson, directory) constructor is gone. VS2's loaders parse raw JsonElement, so we
// supply a passthrough Codec<JsonElement> and keep their apply() bodies unchanged.

val VS_JSON_CODEC: Codec<JsonElement> = Codec.PASSTHROUGH.xmap(
    { dynamic -> dynamic.convert(JsonOps.INSTANCE).value },
    { json -> Dynamic(JsonOps.INSTANCE, json) }
)

// Mirrors the old (Gson, directory) ctor's behaviour: <directory>/<id>.json lookup.
fun vsJsonListerFor(directory: String): FileToIdConverter = FileToIdConverter.json(directory)
