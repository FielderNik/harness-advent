package com.harnessadvent.bootstrap

import com.harnessadvent.adapters.*
import com.harnessadvent.application.*
import com.harnessadvent.domain.*
import com.harnessadvent.persistence.*
import org.koin.core.module.Module
import org.koin.dsl.module

fun harnessModule(config: HarnessConfig): Module = module {
    single { config }
    single { HarnessDatabase(config.databaseUrl) }
    single<ProjectRepository> { ExposedProjectRepository(get()) }
    single<TaskRepository> { ExposedTaskRepository(get()) }
    single<SourceRepository> { ExposedSourceRepository(get()) }
    single { AllowedProjectPolicy(config.allowedProjectPaths) }
    single { SafeProjectScanner() }
    single<CodeAgentRunner> { DisabledCodeAgentRunner() }
    single<ModelProvider> { DisabledModelProvider() }
    single<McpRegistry> { DisabledMcpRegistry() }
    single<GitProvider> { DisabledGitProvider() }
    single { TaskEventStream() }
    single { TaskExecutor(get(), get(), get(), get(), get(), get()) }
    single { ProjectService(get(), get(), get(), get()) }
    single { TaskService(get(), get(), get(), get()) }
    single { ModelProfileService(config.modelProfiles) }
}
