package com.harnessadvent.bootstrap

import com.harnessadvent.api.configureApi
import com.harnessadvent.api.configureMonitoring
import com.harnessadvent.api.configureSerialization
import com.harnessadvent.domain.ModelProvider
import com.harnessadvent.domain.McpRegistry
import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.module() {
    configureHarness(HarnessConfig.load())
}

fun Application.moduleForTests(
    config: HarnessConfig,
    modelProvider: ModelProvider? = null,
    mcpRegistry: McpRegistry? = null,
) {
    configureHarness(config, modelProvider, mcpRegistry)
}

private fun Application.configureHarness(
    config: HarnessConfig,
    modelProvider: ModelProvider? = null,
    mcpRegistry: McpRegistry? = null,
) {
    configureMonitoring()
    configureSerialization()
    install(Koin) {
        slf4jLogger()
        modules(harnessModule(config, modelProvider, mcpRegistry))
    }
    configureApi()
}
