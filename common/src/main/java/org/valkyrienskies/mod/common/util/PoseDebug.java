package org.valkyrienskies.mod.common.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * TEMPORARY diagnostic for the helm-rider pose bug. Logs at INFO under the "VS-TD-POSE"
 * logger, but only when a channel's value actually changes -- so mounting/dismounting a helm
 * produces a couple of lines instead of one per render frame. Remove once the pose is fixed.
 *
 * <p>Lives in common.util (NOT org.valkyrienskies.mod.mixin.*): Mixin owns the mixin package
 * and will try to transform any class loaded from it, so a plain helper referenced from
 * transformed code there throws IllegalClassLoadError.
 */
public final class PoseDebug {

    private static final Logger LOG = LogManager.getLogger("VS-TD-POSE");
    private static final Map<String, String> LAST = new ConcurrentHashMap<>();

    private PoseDebug() {
    }

    public static void change(final String channel, final String value) {
        final String prev = LAST.put(channel, value);
        if (prev == null || !prev.equals(value)) {
            LOG.info("[VS-TD-POSE] {} = {}", channel, value);
        }
    }
}
