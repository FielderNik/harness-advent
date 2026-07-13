package com.harnessadvent.bootstrap

import com.harnessadvent.adapters.*
import com.harnessadvent.application.*
import com.harnessadvent.domain.*
import com.harnessadvent.persistence.*
import org.koin.core.module.Module
import org.koin.dsl.module

fun harnessModule(config: HarnessConfig, modelProviderOverride: ModelProvider? = null): Module = module {
    single { config }
    single { HarnessDatabase(config.databaseUrl) }
    single<ProjectRepository> { ExposedProjectRepository(get()) }
    single<TaskRepository> { ExposedTaskRepository(get()) }
    single<SourceRepository> { ExposedSourceRepository(get()) }
    single { AllowedProjectPolicy(config.allowedProjectPaths) }
    single { SafeProjectScanner() }
    single<ModelProvider> { modelProviderOverride ?: OpenAiCompatibleModelProvider(config.modelConnections) }
    single<McpRegistry> { ConfiguredMcpRegistry(config.mcpServers) }
    single<GitProvider> { DisabledGitProvider() }
    single { TaskEventStream() }
    single { TaskExecutor(get(), get(), get(), get()) }
    single { ProjectService(get(), get(), get(), get()) }
    single { TaskService(get(), get(), get(), get(), get()) }
    single { ModelProfileService(config.modelProfiles) }
    single { HelpCommandService(get()) }
    single { McpService(get()) }
}
