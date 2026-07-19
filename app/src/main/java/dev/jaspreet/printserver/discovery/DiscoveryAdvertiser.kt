package dev.jaspreet.printserver.discovery

interface DiscoveryAdvertiser {
    fun advertiseIpp(name: String, port: Int, txt: Map<String, String>)
    fun advertiseRaw(name: String, port: Int)
    fun advertiseEscl(name: String, port: Int, txt: Map<String, String>)
    /** Withdraw all advertisements (network change, printer unplug, shutdown). */
    fun stopAll()
}
