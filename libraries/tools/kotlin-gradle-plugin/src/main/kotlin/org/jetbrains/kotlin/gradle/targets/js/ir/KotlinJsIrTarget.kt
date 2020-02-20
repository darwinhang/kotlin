/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.ir

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.jetbrains.kotlin.gradle.plugin.AbstractKotlinTargetConfigurator.Companion.runTaskNameSuffix
import org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetWithTests
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinOnlyTarget
import org.jetbrains.kotlin.gradle.plugin.removeJsCompilerSuffix
import org.jetbrains.kotlin.gradle.targets.js.JsAggregatingExecutionSource
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsProducingType
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsReportAggregatingTestRun
import org.jetbrains.kotlin.gradle.targets.js.dsl.*
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import javax.inject.Inject

open class KotlinJsIrTarget
@Inject
constructor(
    project: Project,
    platformType: KotlinPlatformType,
    internal val mixedMode: Boolean
) :
    KotlinOnlyTarget<KotlinJsIrCompilation>(project, platformType),
    KotlinTargetWithTests<JsAggregatingExecutionSource, KotlinJsReportAggregatingTestRun>,
    KotlinJsTargetDsl,
    KotlinJsSubTargetContainerDsl {
    override lateinit var testRuns: NamedDomainObjectContainer<KotlinJsReportAggregatingTestRun>
        internal set

    val disambiguationClassifierInPlatform: String?
        get() = disambiguationClassifier?.removeJsCompilerSuffix(KotlinJsCompilerType.IR)

    var producingType: KotlinJsProducingType? = null

    private val runTaskName get() = lowerCamelCaseName(disambiguationClassifier, runTaskNameSuffix)
    val runTask: Task
        get() = project.tasks.maybeCreate(runTaskName).also {
            it.description = "Run js on all configured platforms"
        }

    private val browserLazyDelegate = lazy {
        project.objects.newInstance(KotlinBrowserJsIr::class.java, this).also {
            it.configure()
            browserConfiguredHandlers.forEach { handler ->
                handler(it)
            }
            browserConfiguredHandlers.clear()
        }
    }

    private val browserConfiguredHandlers = mutableListOf<KotlinJsBrowserDsl.() -> Unit>()

    override val browser by browserLazyDelegate

    override val isBrowserConfigured: Boolean
        get() = browserLazyDelegate.isInitialized()

    override fun browser(body: KotlinJsBrowserDsl.() -> Unit) {
        body(browser)
    }

    private val nodejsLazyDelegate = lazy {
        project.objects.newInstance(KotlinNodeJsIr::class.java, this).also {
            it.configure()
            nodejsConfiguredHandlers.forEach { handler ->
                handler(it)
            }

            nodejsConfiguredHandlers.clear()
        }
    }

    private val nodejsConfiguredHandlers = mutableListOf<KotlinJsNodeDsl.() -> Unit>()

    override val nodejs by nodejsLazyDelegate

    override val isNodejsConfigured: Boolean
        get() = nodejsLazyDelegate.isInitialized()

    override fun nodejs(body: KotlinJsNodeDsl.() -> Unit) {
        body(nodejs)
    }

    override fun produceKotlinLibrary() {
        produce(KotlinJsProducingType.KOTLIN_LIBRARY)
    }

    override fun produceExecutable() {
        produce(KotlinJsProducingType.EXECUTABLE) {
            (this as KotlinJsIrSubTarget).produceExecutable()
        }
    }

    private fun produce(
        producingType: KotlinJsProducingType,
        producer: KotlinJsSubTargetDsl.() -> Unit = {}
    ) {
        check(this.producingType == null || this.producingType == producingType) {
            "Only one producing type supported. Try to set $producingType but previously ${this.producingType} found"
        }

        this.producingType = producingType

        whenBrowserConfigured {
            producer()
        }

        whenNodejsConfigured {
            producer()
        }
    }

    override fun whenBrowserConfigured(body: KotlinJsBrowserDsl.() -> Unit) {
        if (browserLazyDelegate.isInitialized()) {
            browser(body)
        } else {
            browserConfiguredHandlers += body
        }
    }

    override fun whenNodejsConfigured(body: KotlinJsNodeDsl.() -> Unit) {
        if (nodejsLazyDelegate.isInitialized()) {
            nodejs(body)
        } else {
            nodejsConfiguredHandlers += body
        }
    }

    override fun useCommonJs() {
        compilations.all {
            it.compileKotlinTask.kotlinOptions {
                moduleKind = "commonjs"
                sourceMap = true
                sourceMapEmbedSources = null
            }
        }
    }
}