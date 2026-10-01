package io.github.prashanthsams.pytesthtmlreporter.settings

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.run.InterpreterResolution
import io.github.prashanthsams.pytesthtmlreporter.run.PythonInterpreterProvider
import io.github.prashanthsams.pytesthtmlreporter.core.ReportScanner
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.JEditorPane
import java.nio.file.Path

/** Settings | Tools | pytest-html-reporter. */
class ReporterConfigurable(private val project: Project) :
    BoundSearchableConfigurable(message("settings.displayName"), ID, ID) {

    private val settings get() = ReporterSettings.getInstance(project).state
    private val workspace get() = ReporterWorkspaceState.getInstance(project).state

    override fun getHelpTopic(): String? = null

    override fun createPanel(): DialogPanel {
        // The (Project) constructor that 2026.2 prefers does not exist in 2025.1, the oldest supported build.
        val environment = EnvironmentVariablesComponent()
        lateinit var excludedField: ExpandableTextField
        return panel {
            group(message("settings.group.python")) {
                row(message("settings.interpreter")) {
                    val interpreter = cell(browseField(FileChooserDescriptorFactory.singleFile()))
                        .bindText({ workspace.interpreterPath.orEmpty() }, { workspace.interpreterPath = it.trim() })
                        .align(AlignX.FILL)
                        .comment(message("settings.interpreter.comment.detecting"))
                    interpreter.comment?.let(::showDetectedInterpreter)
                }
            }
            group(message("settings.group.pytest")) {
                row(message("settings.workingDirectory")) {
                    cell(browseField(FileChooserDescriptorFactory.singleDir()))
                        .bindText({ settings.workingDirectory.orEmpty() }, { settings.workingDirectory = it.trim() })
                        .align(AlignX.FILL)
                        .comment(message("settings.workingDirectory.comment"))
                }
                row(message("settings.targets")) {
                    textField()
                        .bindText({ settings.targets.orEmpty() }, { settings.targets = it.trim() })
                        .align(AlignX.FILL)
                        .comment(message("settings.targets.comment"))
                }
                row(message("settings.arguments")) {
                    expandableTextField()
                        .bindText({ settings.pytestArguments.orEmpty() }, { settings.pytestArguments = it.trim() })
                        .align(AlignX.FILL)
                        .comment(message("settings.arguments.comment"))
                }
                row {
                    cell(environment)
                        .align(AlignX.FILL)
                        .onReset { environment.envData = EnvironmentVariablesData.create(settings.environmentVariables, settings.passParentEnvironment) }
                        .onIsModified {
                            environment.envs != settings.environmentVariables || environment.isPassParentEnvs != settings.passParentEnvironment
                        }
                        .onApply {
                            settings.environmentVariables = LinkedHashMap(environment.envs)
                            settings.passParentEnvironment = environment.isPassParentEnvs
                        }
                }
            }
            group(message("settings.group.report")) {
                row(message("settings.output")) {
                    textField()
                        .bindText({ settings.outputPath.orEmpty() }, { settings.outputPath = it.trim() })
                        .align(AlignX.FILL)
                        .comment(message("settings.output.comment"))
                }
                row {
                    checkBox(message("settings.respectConfiguration"))
                        .bindSelected({ settings.respectPytestConfiguration }, { settings.respectPytestConfiguration = it })
                        .comment(message("settings.respectConfiguration.comment"))
                }
                row {
                    checkBox(message("settings.openAfterRun"))
                        .bindSelected({ settings.openReportAfterRun }, { settings.openReportAfterRun = it })
                }
                row {
                    checkBox(message("settings.autoRefresh"))
                        .bindSelected({ settings.autoRefresh }, { settings.autoRefresh = it })
                }
                row {
                    checkBox(message("settings.followTheme"))
                        .bindSelected({ settings.followIdeTheme }, { settings.followIdeTheme = it })
                }
                row {
                    checkBox(message("settings.blockRemote"))
                        .bindSelected({ settings.blockRemoteContent }, { settings.blockRemoteContent = it })
                        .comment(message("settings.blockRemote.comment"))
                }
            }
            group(message("settings.group.discovery")) {
                row(message("settings.depth")) {
                    spinner(1..12)
                        .bindIntValue({ settings.discoveryDepth }, { settings.discoveryDepth = it })
                }
                row(message("settings.excluded")) {
                    excludedField = expandableTextField()
                        .bindText({ settings.excludedDirectories.orEmpty() }, { settings.excludedDirectories = it.trim() })
                        .align(AlignX.FILL)
                        .comment(message("settings.excluded.comment"))
                        .component
                }
                row(message("settings.locations")) {
                    expandableTextField()
                        .bindText({ settings.additionalLocations.orEmpty() }, { settings.additionalLocations = it.trim() })
                        .align(AlignX.FILL)
                        .comment(message("settings.locations.comment"))
                }
                row {
                    link(message("settings.excluded.reset")) {
                        excludedField.text = ReportScanner.DEFAULT_EXCLUDED_NAMES.joinToString(", ")
                    }
                }
            }
            group(message("settings.group.runConfigurations")) {
                row {
                    checkBox(message("settings.runConfigurations"))
                        .bindSelected({ settings.refreshAfterRunConfigurations }, { settings.refreshAfterRunConfigurations = it })
                        .comment(message("settings.runConfigurations.comment"))
                }
            }
        }
    }

    private fun browseField(descriptor: com.intellij.openapi.fileChooser.FileChooserDescriptor) =
        TextFieldWithBrowseButton().apply { addBrowseFolderListener(project, descriptor) }

    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(ReporterSettingsListener.TOPIC).settingsChanged()
    }

    /** Looks the project interpreter up off the UI thread (module lookups are slow operations) and shows it as a hint. */
    private fun showDetectedInterpreter(comment: JEditorPane) {
        ReadAction.nonBlocking<String> { interpreterComment() }
            .expireWhen { project.isDisposed }
            .finishOnUiThread(ModalityState.any()) { comment.text = it }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun interpreterComment(): String {
        val detected = PythonInterpreterProvider.EP_NAME.extensionList
            .firstNotNullOfOrNull { it.findInterpreter(project, project.basePath?.let(Path::of)) }
        return when (detected) {
            // Shortened in the middle: an unbreakable long path would widen the whole settings page.
            is InterpreterResolution.Local -> message(
                "settings.interpreter.comment.detected",
                StringUtil.shortenPathWithEllipsis(FileUtil.getLocationRelativeToUserHome(detected.executable.toString()), 45),
            )
            is InterpreterResolution.Remote -> message("settings.interpreter.comment.remote", detected.name)
            is InterpreterResolution.Unavailable -> message("settings.interpreter.comment.none")
            null -> if (PythonInterpreterProvider.EP_NAME.extensionList.isEmpty()) message("settings.interpreter.comment.noPython")
            else message("settings.interpreter.comment.none")
        }
    }

    companion object {
        const val ID = "io.github.prashanthsams.pytesthtmlreporter.settings"
    }
}
