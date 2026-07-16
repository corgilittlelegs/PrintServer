package dev.jaspreet.printserver.relay

/** Job-activity hooks; the service maps these to a reference-counted wakelock. */
interface ActivityMonitor {
    fun begin()
    fun end()

    companion object {
        val NONE = object : ActivityMonitor {
            override fun begin() {}
            override fun end() {}
        }
    }
}
