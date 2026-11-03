package com.pokedaisy.relay

fun main() {
    val config = RelayConfig.fromEnv()
    val server = RelayServer(config)
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    println("relay listening on ${server.port}, health on ${server.healthPort}")
    Thread.currentThread().join()
}
