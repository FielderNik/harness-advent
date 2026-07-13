package com.harnessadvent.bootstrap

import com.harnessadvent.api.configureApi
import com.harnessadvent.api.configureMonitoring
import com.harnessadvent.api.configureSerialization
import com.harnessadvent.domain.ModelProvider
import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.module() {
    configureHarness(HarnessConfig.load())
}

fun Application.moduleForTests(config: HarnessConfig, modelProvider: ModelProvider? = null) {
    configureHarness(config, modelProvider)
}

private fun Application.configureHarness(config: HarnessConfig, modelProvider: ModelProvider? = null) {
    configureMonitoring()
    configureSerialization()
    install(Koin) {
        slf4jLogger()
        modules(harnessModule(config, modelProvider))
    }
    configureApi()
}
