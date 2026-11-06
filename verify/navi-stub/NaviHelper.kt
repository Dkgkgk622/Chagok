package ai.pleos.playground.navi.helper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
class NaviHelper(context: android.content.Context) {
    fun initialize() {}
    fun addListener(listener: NaviHelperEventListener) {}
    fun removeListener(listener: NaviHelperEventListener) {}
    fun getCurrentLocationInfo() {}
    fun release() {}
}
