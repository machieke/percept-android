package org.takopi.percept.app

import android.content.Context

/** Process-wide wiring shared by the activity and the foreground service. */
object PerceptRuntime {
    @Volatile
    private var controllerInstance: SessionController? = null

    fun controller(context: Context): SessionController =
        controllerInstance ?: synchronized(this) {
            controllerInstance ?: SessionController(context.applicationContext)
                .also { controllerInstance = it }
        }

    /** Test hook: replace or clear the shared controller. */
    fun setControllerForTest(controller: SessionController?) {
        controllerInstance = controller
    }
}
