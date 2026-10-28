package org.stypox.dicio.eval

/**
 * Which branch of the command dispatcher handled the last phrase. Used by the in-app command check
 * screen, so that routing can be verified without speaking.
 */
object CommandTrace {
    @Volatile
    var stage: String = ""

    fun set(value: String) {
        stage = value
    }

    fun reset(stage: String) {
        this.stage = stage
    }
}
