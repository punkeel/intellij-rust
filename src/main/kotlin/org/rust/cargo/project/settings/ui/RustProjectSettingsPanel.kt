/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.settings.ui

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.AppUIExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.components.Link
import com.intellij.ui.layout.LayoutBuilder
import org.rust.cargo.project.RsToolchainPathChoosingComboBox
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.cargo.toolchain.RsToolchainProvider
import org.rust.cargo.toolchain.flavors.RsToolchainFlavor
import org.rust.cargo.toolchain.tools.Rustup
import org.rust.cargo.toolchain.tools.rustc
import org.rust.cargo.toolchain.tools.rustup
import org.rust.openapiext.UiDebouncer
import org.rust.openapiext.pathToDirectoryTextField
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class RustProjectSettingsPanel(
    private val cargoProjectDir: Path = Paths.get("."),
    private val updateListener: (() -> Unit)? = null
) : Disposable {
    data class Data(
        val toolchain: RsToolchainBase?,
        val explicitPathToStdlib: String?
    )

    override fun dispose() {}

    private val versionUpdateDebouncer = UiDebouncer(this)

    private val pathToToolchainField = RsToolchainPathChoosingComboBox().apply {
        childComponent.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) {
                update()
            }
        }
    }

    private val pathToStdlibField = pathToDirectoryTextField(this,
        "Select directory with standard library source code")

    private var fetchedSysroot: String? = null

    private val downloadStdlibLink = Link("Download via Rustup") {
        val homePath = pathToToolchainField.selectedPath ?: return@Link
        val rustup = RsToolchainProvider.getToolchain(homePath)?.rustup ?: return@Link
        object : Task.Modal(null, "Downloading Rust Standard Library", true) {
            override fun onSuccess() = update()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Installing using Rustup..."

                rustup.downloadStdlib(this@RustProjectSettingsPanel, listener = object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        indicator.text2 = event.text.trim()
                    }
                })
            }
        }.queue()
    }.apply { isVisible = false }

    private val toolchainVersion = JLabel()

    var data: Data
        get() {
            val toolchain = pathToToolchainField.selectedPath?.let { RsToolchainProvider.getToolchain(it) }
            return Data(
                toolchain = toolchain,
                explicitPathToStdlib = pathToStdlibField.text.blankToNull()
                    ?.takeIf { toolchain?.rustup == null && it != fetchedSysroot }
            )
        }
        set(value) {
            // https://youtrack.jetbrains.com/issue/KT-16367
            pathToToolchainField.selectedPath = value.toolchain?.location
            pathToStdlibField.text = value.explicitPathToStdlib ?: ""
            update()
        }

    fun attachTo(layout: LayoutBuilder) = with(layout) {
        data = Data(
            toolchain = ProjectManager.getInstance().defaultProject.toolchain ?: RsToolchainBase.suggest(),
            explicitPathToStdlib = null
        )

        row("Toolchain location:") { wrapComponent(pathToToolchainField)(growX, pushX) }
        row("Toolchain version:") { toolchainVersion() }
        row("Standard library:") { wrapComponent(pathToStdlibField)(growX, pushX) }
        row("") { downloadStdlibLink() }

        addToolchainsAsync(pathToToolchainField) {
            RsToolchainFlavor.getApplicableFlavors().flatMap { it.suggestHomePaths() }.distinct()
        }
    }

    @Throws(ConfigurationException::class)
    fun validateSettings() {
        val toolchain = data.toolchain ?: return
        if (!toolchain.looksLikeValidToolchain()) {
            throw ConfigurationException("Invalid toolchain location: can't find Cargo in ${toolchain.location}")
        }
    }

    private fun update() {
        val pathToToolchain = pathToToolchainField.selectedPath
        versionUpdateDebouncer.run(
            onPooledThread = {
                val toolchain = pathToToolchain?.let { RsToolchainProvider.getToolchain(it) }
                val rustc = toolchain?.rustc()
                val rustup = toolchain?.rustup
                val rustcVersion = rustc?.queryVersion()?.semver
                val stdlibLocation = rustc?.getStdlibFromSysroot(cargoProjectDir)?.presentableUrl
                Triple(rustcVersion, stdlibLocation, rustup != null)
            },
            onUiThread = { (rustcVersion, stdlibLocation, hasRustup) ->
                downloadStdlibLink.isVisible = hasRustup && stdlibLocation == null

                pathToStdlibField.isEditable = !hasRustup
                pathToStdlibField.button.isEnabled = !hasRustup
                if (stdlibLocation != null && (pathToStdlibField.text.isBlank() || hasRustup)) {
                    pathToStdlibField.text = stdlibLocation
                }
                fetchedSysroot = stdlibLocation

                if (rustcVersion == null) {
                    toolchainVersion.text = "N/A"
                    toolchainVersion.foreground = JBColor.RED
                } else {
                    toolchainVersion.text = rustcVersion.parsedVersion
                    toolchainVersion.foreground = JBColor.foreground()
                }
                updateListener?.invoke()
            }
        )
    }

    private val RsToolchainBase.rustup: Rustup? get() = rustup(cargoProjectDir)
}

private fun String.blankToNull(): String? = ifBlank { null }

private fun wrapComponent(component: JComponent): JComponent =
    JPanel(BorderLayout()).apply {
        add(component, BorderLayout.NORTH)
    }

/**
 * Obtains a list of toolchains on a pool using [toolchainObtainer], then fills [toolchainComboBox] on the EDT.
 */
@Suppress("UnstableApiUsage")
private fun addToolchainsAsync(
    toolchainComboBox: RsToolchainPathChoosingComboBox,
    toolchainObtainer: () -> List<Path>
) {
    ApplicationManager.getApplication().executeOnPooledThread {
        val executor = AppUIExecutor.onUiThread(ModalityState.any())
        executor.execute { toolchainComboBox.setBusy(true) }
        var toolchains = emptyList<Path>()
        try {
            toolchains = toolchainObtainer()
        } finally {
            executor.execute {
                toolchainComboBox.setBusy(false)
                val selectedPath = toolchainComboBox.selectedPath
                toolchainComboBox.childComponent.removeAllItems()
                toolchains.forEach(toolchainComboBox.childComponent::addItem)
                toolchainComboBox.selectedPath = selectedPath
            }
        }
    }
}
