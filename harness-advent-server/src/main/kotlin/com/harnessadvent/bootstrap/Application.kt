package com.harnessadvent.bootstrap

import com.harnessadvent.api.configureApi
import com.harnessadvent.api.configureMonitoring
import com.harnessadvent.api.configureSerialization
import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.module() = module(HarnessConfig.fromEnvironment())

fun Application.module(config: HarnessConfig) {
    configureMonitoring()
    configureSerialization()
    install(Koin) {
        slf4jLogger()
        modules(harnessModule(config))
    }
    configureApi()
}
