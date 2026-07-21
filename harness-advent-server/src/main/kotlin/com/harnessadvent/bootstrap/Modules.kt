package com.harnessadvent.bootstrap

import com.harnessadvent.adapters.*
import com.harnessadvent.application.*
import com.harnessadvent.domain.*
import com.harnessadvent.persistence.*
import org.koin.core.module.Module
import org.koin.dsl.module

fun harnessModule(
    config: HarnessConfig,
    modelProviderOverride: ModelProvider? = null,
    mcpRegistryOverride: McpRegistry? = null,
): Module = module {
    single { config }
    single { HarnessDatabase(config.databaseUrl) }
    single<ProjectRepository> { ExposedProjectRepository(get()) }
    single<TaskRepository> { ExposedTaskRepository(get()) }
    single<SourceRepository> { ExposedSourceRepository(get()) }
    single<TestCoveragePlanRepository> { ExposedTestCoveragePlanRepository(get()) }
    single { AllowedProjectPolicy(config.allowedProjectPaths) }
    single { SafeProjectScanner() }
    single<ProjectFiles> { SafeProjectFiles(get()) }
    single<ModelProvider> { modelProviderOverride ?: OpenAiCompatibleModelProvider(config.modelConnections) }
    single<McpRegistry> { mcpRegistryOverride ?: ConfiguredMcpRegistry(config.mcpServers) }
    single<GitProvider> { DisabledGitProvider() }
    single<OpenPullRequestReader> {
        if (config.testGeneration.isConfigured) GitHubOpenPullRequestReader(config.testGeneration) else DisabledOpenPullRequestReader()
    }
    single<PullRequestPublisher> {
        if (config.testGeneration.isConfigured) GitHubPullRequestPublisher(config.testGeneration) else DisabledPullRequestPublisher()
    }
    single<TestGenerationWorkspace> {
        if (config.testGeneration.isConfigured) LocalTestGenerationWorkspace(config.testGeneration) else DisabledTestGenerationWorkspace()
    }
    single { TaskEventStream() }
    single { TaskExecutor(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), config.supportYouTrackServerId) }
    single { ProjectService(get(), get(), get(), get()) }
    single { TaskService(get(), get(), get(), get(), get(), config.codeReviewAutoApprovedContextProfiles) }
    single { TestCoverageService(get(), get(), get(), get(), get(), get()) }
    single { ModelProfileService(config.modelProfiles) }
    single { HelpCommandService(get()) }
    single { McpService(get()) }
}
